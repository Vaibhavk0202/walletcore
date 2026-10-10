package com.vaibhav.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vaibhav.ledger.account.Account;
import com.vaibhav.ledger.account.AccountService;
import com.vaibhav.ledger.account.SystemAccounts;
import com.vaibhav.ledger.common.ApiException;
import com.vaibhav.ledger.ledger.Direction;
import com.vaibhav.ledger.ledger.LedgerEntry;
import com.vaibhav.ledger.ledger.LedgerTransaction;
import com.vaibhav.ledger.transfer.TransferService;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerIntegrationTest {

    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired JdbcClient jdbc;

    LedgerDriftChecker driftChecker;

    @BeforeEach
    void setUp() {
        driftChecker = new LedgerDriftChecker(jdbc);
    }

    @Test
    void depositCreditsWalletAndDebitsSettlement() {
        Account wallet = newWallet();

        LedgerTransaction txn = transferService.deposit(wallet.id(), 10_000, "Top-up");

        assertThat(txn.entries()).hasSize(2);
        assertThat(txn.entries()).extracting(LedgerEntry::accountId, LedgerEntry::direction, LedgerEntry::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SystemAccounts.SETTLEMENT_INR, Direction.DEBIT, 10_000L),
                        org.assertj.core.groups.Tuple.tuple(wallet.id(), Direction.CREDIT, 10_000L));
        assertThat(accountService.get(wallet.id()).balance()).isEqualTo(10_000);
        driftChecker.assertNoDrift();
    }

    @Test
    void transferMovesMoneyWithTwoBalancedEntries() {
        Account alice = newWallet();
        Account bob = newWallet();
        transferService.deposit(alice.id(), 1_000, "Top-up");

        LedgerTransaction txn = transferService.transfer(alice.id(), bob.id(), 500, "Dinner");

        assertThat(txn.entries()).extracting(LedgerEntry::balanceAfter).containsExactly(500L, 500L);
        assertThat(accountService.get(alice.id()).balance()).isEqualTo(500);
        assertThat(accountService.get(bob.id()).balance()).isEqualTo(500);
        driftChecker.assertNoDrift();
    }

    @Test
    void insufficientFundsIsRejectedAndNothingIsWritten() {
        Account alice = newWallet();
        Account bob = newWallet();
        transferService.deposit(alice.id(), 300, "Top-up");

        assertThatThrownBy(() -> transferService.transfer(alice.id(), bob.id(), 500, "Too much"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        assertThat(accountService.get(alice.id()).balance()).isEqualTo(300);
        assertThat(accountService.get(bob.id()).balance()).isZero();
        assertThat(accountService.statement(bob.id(), null, 50)).isEmpty();
        driftChecker.assertNoDrift();
    }

    @Test
    void transferToSameAccountIsRejected() {
        Account alice = newWallet();

        assertThatThrownBy(() -> transferService.transfer(alice.id(), alice.id(), 100, "Self"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void statementIsNewestFirstAndPaginates() {
        Account alice = newWallet();
        Account bob = newWallet();
        transferService.deposit(alice.id(), 1_000, "Top-up");
        transferService.transfer(alice.id(), bob.id(), 100, "One");
        transferService.transfer(alice.id(), bob.id(), 200, "Two");

        List<LedgerEntry> firstPage = accountService.statement(alice.id(), null, 2);
        List<LedgerEntry> secondPage = accountService.statement(alice.id(), firstPage.getLast().id(), 2);

        assertThat(firstPage).extracting(LedgerEntry::balanceAfter).containsExactly(700L, 900L);
        assertThat(secondPage).extracting(LedgerEntry::balanceAfter).containsExactly(1_000L);
    }

    @Test
    void manyRandomTransfersLeaveZeroDrift() {
        Random random = new Random(42);
        List<Account> wallets = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Account wallet = newWallet();
            transferService.deposit(wallet.id(), 10_000, "Seed");
            wallets.add(wallet);
        }

        int rejected = 0;
        for (int i = 0; i < 500; i++) {
            Account from = wallets.get(random.nextInt(wallets.size()));
            Account to = wallets.get(random.nextInt(wallets.size()));
            if (from.id().equals(to.id())) {
                continue;
            }
            try {
                transferService.transfer(from.id(), to.id(), 1 + random.nextInt(3_000), "Random");
            } catch (ApiException e) {
                rejected++; // insufficient funds is expected sometimes
            }
        }

        long walletTotal = wallets.stream().mapToLong(w -> accountService.get(w.id()).balance()).sum();
        assertThat(walletTotal).as("money only moves between these wallets").isEqualTo(10 * 10_000);
        assertThat(rejected).as("some transfers should hit insufficient funds").isPositive();
        driftChecker.assertNoDrift();
    }

    private Account newWallet() {
        return accountService.openWallet(UUID.randomUUID(), "INR");
    }
}