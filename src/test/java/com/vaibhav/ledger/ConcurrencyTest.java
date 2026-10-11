package com.vaibhav.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.vaibhav.ledger.account.Account;
import com.vaibhav.ledger.account.AccountService;
import com.vaibhav.ledger.common.ApiException;
import com.vaibhav.ledger.transfer.TransferService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcurrencyTest {

    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired JdbcClient jdbc;

    LedgerDriftChecker driftChecker;

    @BeforeEach
    void setUp() {
        driftChecker = new LedgerDriftChecker(jdbc);
    }

    @Test
    void doubleSpendIsImpossible() throws Exception {
        Account alice = newWallet();
        transferService.deposit(alice.id(), 1_000, "Seed");
        List<Account> receivers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            receivers.add(newWallet());
        }

        // 20 threads all try to spend Alice's whole balance at the same instant.
        Outcomes outcomes = runConcurrently(20, i ->
                transferService.transfer(alice.id(), receivers.get(i).id(), 1_000, "Race"));

        assertThat(outcomes.unexpected).as("unexpected errors").isEmpty();
        assertThat(outcomes.succeeded.get()).as("exactly one transfer wins").isEqualTo(1);
        assertThat(outcomes.rejected.get()).isEqualTo(19);
        assertThat(accountService.get(alice.id()).balance()).isZero();
        driftChecker.assertNoDrift();
    }

    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        Account alice = newWallet();
        Account bob = newWallet();
        transferService.deposit(alice.id(), 100_000, "Seed");
        transferService.deposit(bob.id(), 100_000, "Seed");

        // Half the tasks send A -> B, half send B -> A. Without ordered locking this deadlocks.
        Outcomes outcomes = runConcurrently(400, i -> {
            if (i % 2 == 0) {
                transferService.transfer(alice.id(), bob.id(), 10, "A to B");
            } else {
                transferService.transfer(bob.id(), alice.id(), 10, "B to A");
            }
        });

        assertThat(outcomes.unexpected).as("unexpected errors").isEmpty();
        assertThat(outcomes.succeeded.get()).isEqualTo(400);
        assertThat(accountService.get(alice.id()).balance()).isEqualTo(100_000);
        assertThat(accountService.get(bob.id()).balance()).isEqualTo(100_000);
        driftChecker.assertNoDrift();
    }

    @Test
    void thousandConcurrentTransfersLeaveZeroDrift() throws Exception {
        List<Account> wallets = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Account wallet = newWallet();
            transferService.deposit(wallet.id(), 10_000, "Seed");
            wallets.add(wallet);
        }
        Random random = new Random(7);
        List<int[]> plan = new ArrayList<>();
        while (plan.size() < 1_000) {
            int from = random.nextInt(wallets.size());
            int to = random.nextInt(wallets.size());
            if (from != to) {
                plan.add(new int[] {from, to, 1 + random.nextInt(5_000)});
            }
        }

        Outcomes outcomes = runConcurrently(plan.size(), i -> {
            int[] p = plan.get(i);
            transferService.transfer(wallets.get(p[0]).id(), wallets.get(p[1]).id(), p[2], "Load");
        });

        long total = wallets.stream().mapToLong(w -> accountService.get(w.id()).balance()).sum();
        System.out.printf("1000 concurrent transfers: %d succeeded, %d rejected, %d unexpected%n",
                outcomes.succeeded.get(), outcomes.rejected.get(), outcomes.unexpected.size());
        assertThat(outcomes.unexpected).as("unexpected errors").isEmpty();
        assertThat(outcomes.succeeded.get() + outcomes.rejected.get()).isEqualTo(1_000);
        assertThat(total).as("money is conserved").isEqualTo(20 * 10_000);
        assertThat(wallets).allSatisfy(w -> assertThat(accountService.get(w.id()).balance()).isNotNegative());
        driftChecker.assertNoDrift();
    }

    // ---- helpers ----

    interface Task {
        void run(int index) throws Exception;
    }

    static class Outcomes {
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final Map<String, Integer> unexpected = new ConcurrentHashMap<>();
    }

    /** Starts every task at the same moment and waits for all of them to finish. */
    private static Outcomes runConcurrently(int tasks, Task task) throws InterruptedException {
        Outcomes outcomes = new Outcomes();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 0; i < tasks; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    task.run(index);
                    outcomes.succeeded.incrementAndGet();
                } catch (ApiException e) {
                    outcomes.rejected.incrementAndGet(); // a clean business rejection, e.g. insufficient funds
                } catch (Exception e) {
                    outcomes.unexpected.merge(e.getClass().getSimpleName(), 1, Integer::sum);
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(done.await(2, TimeUnit.MINUTES)).as("all tasks finished").isTrue();
        pool.shutdown();
        return outcomes;
    }

    private Account newWallet() {
        return accountService.openWallet(UUID.randomUUID(), "INR");
    }
}