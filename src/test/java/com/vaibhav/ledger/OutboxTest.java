package com.vaibhav.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.vaibhav.ledger.account.Account;
import com.vaibhav.ledger.account.AccountService;
import com.vaibhav.ledger.common.ApiException;
import com.vaibhav.ledger.ledger.LedgerTransaction;
import com.vaibhav.ledger.transfer.TransferService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxTest {

    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper jsonMapper;
    @Autowired KafkaConnectionDetails kafkaConnection;

    @Test
    void transferWritesExactlyOneOutboxEvent() {
        Account alice = newWallet();
        Account bob = newWallet();
        transferService.deposit(alice.id(), 1_000, "Seed");

        LedgerTransaction txn = transferService.transfer(alice.id(), bob.id(), 300, "Dinner");

        assertThat(jdbc.sql("SELECT event_type FROM outbox_events WHERE aggregate_id = :id")
                .param("id", txn.id())
                .query(String.class)
                .list())
                .containsExactly("TransactionPosted");
    }

    @Test
    void failedTransferWritesNoEvent() {
        Account alice = newWallet();
        Account bob = newWallet();
        long before = countEvents();

        assertThatThrownBy(() -> transferService.transfer(alice.id(), bob.id(), 300, "No money"))
                .isInstanceOf(ApiException.class);

        assertThat(countEvents()).as("a rolled-back transfer leaves no event behind").isEqualTo(before);
    }

    @Test
    void eventsArePublishedToKafka() {
        Account alice = newWallet();
        Account bob = newWallet();
        transferService.deposit(alice.id(), 1_000, "Seed");

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("ledger.transactions"));
            LedgerTransaction txn = transferService.transfer(alice.id(), bob.id(), 300, "Dinner");

            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(received::add);
                return received.stream().anyMatch(r -> r.key().equals(txn.id().toString()));
            });

            ConsumerRecord<String, String> record = received.stream()
                    .filter(r -> r.key().equals(txn.id().toString()))
                    .findFirst()
                    .orElseThrow();
            JsonNode payload = jsonMapper.readTree(record.value());
            assertThat(payload.get("type").asString()).isEqualTo("TRANSFER");
            assertThat(payload.get("entries")).hasSize(2);
            assertThat(header(record, "event-type")).isEqualTo("TransactionPosted");
            assertThat(header(record, "event-id")).isNotBlank();
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                        .query(Long.class)
                        .single())
                        .as("the relay marks everything it sent as published")
                        .isZero());
    }

    // ---- helpers ----

    private KafkaConsumer<String, String> newConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private long countEvents() {
        return jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single();
    }

    private Account newWallet() {
        return accountService.openWallet(UUID.randomUUID(), "INR");
    }
}