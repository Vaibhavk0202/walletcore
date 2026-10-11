package com.vaibhav.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.vaibhav.ledger.account.Account;
import com.vaibhav.ledger.account.AccountService;
import com.vaibhav.ledger.transfer.TransferService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IdempotencyTest {

    @LocalServerPort int port;
    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired JsonMapper jsonMapper;
    @Autowired JdbcClient jdbc;

    RestClient http;
    Account alice;
    Account bob;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://localhost:" + port);
        alice = accountService.openWallet(UUID.randomUUID(), "INR");
        bob = accountService.openWallet(UUID.randomUUID(), "INR");
        transferService.deposit(alice.id(), 1_000, "Seed");
    }

    @Test
    void retryWithSameKeyMovesMoneyOnce() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = postTransfer(key, 300);
        ResponseEntity<String> retry = postTransfer(key, 300);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(transactionId(retry)).as("the retry gets the same transaction back").isEqualTo(transactionId(first));
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(accountService.get(alice.id()).balance()).as("charged once, not twice").isEqualTo(700);
        new LedgerDriftChecker(jdbc).assertNoDrift();
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        String key = UUID.randomUUID().toString();

        postTransfer(key, 300);
        ResponseEntity<String> reused = postTransfer(key, 400);

        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(accountService.get(alice.id()).balance()).isEqualTo(700);
    }

    @Test
    void concurrentRetriesWithSameKeyMoveMoneyOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return postTransfer(key, 300);
            }));
        }
        startGate.countDown();

        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            responses.add(future.get());
        }
        pool.shutdown();

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(201));
        assertThat(responses).extracting(this::transactionId).as("everyone sees one transaction").containsOnly(
                transactionId(responses.getFirst()));
        assertThat(responses).filteredOn(r -> "false".equals(r.getHeaders().getFirst("Idempotent-Replayed")))
                .as("exactly one request did the work").hasSize(1);
        assertThat(accountService.get(alice.id()).balance()).isEqualTo(700);
        new LedgerDriftChecker(jdbc).assertNoDrift();
    }

    @Test
    void missingKeyIsRejected() {
        ResponseEntity<String> response = post("/api/v1/transfers", null,
                Map.of("fromAccountId", alice.id(), "toAccountId", bob.id(), "amount", 300));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(accountService.get(alice.id()).balance()).isEqualTo(1_000);
    }

    @Test
    void failedRequestIsNotRemembered() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> tooMuch = postTransfer(key, 5_000);
        transferService.deposit(alice.id(), 5_000, "Top-up");
        ResponseEntity<String> retry = postTransfer(key, 5_000);

        assertThat(tooMuch.getStatusCode().value()).isEqualTo(422);
        assertThat(retry.getStatusCode().value()).as("a failed attempt moved nothing, so it may be retried")
                .isEqualTo(201);
        assertThat(accountService.get(alice.id()).balance()).isEqualTo(1_000);
    }

    // ---- helpers ----

    private ResponseEntity<String> postTransfer(String key, long amount) {
        return post("/api/v1/transfers", key,
                Map.of("fromAccountId", alice.id(), "toAccountId", bob.id(), "amount", amount));
    }

    private ResponseEntity<String> post(String path, String idempotencyKey, Map<String, Object> body) {
        return http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (idempotencyKey != null) {
                        h.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { }) // let the test inspect errors
                .toEntity(String.class);
    }

    private String transactionId(ResponseEntity<String> response) {
        return jsonMapper.readTree(response.getBody()).get("id").asString();
    }
}