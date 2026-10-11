package com.vaibhav.ledger.ledger;

import java.util.HashMap;
import java.util.Map;
import com.vaibhav.ledger.account.Account;
import com.vaibhav.ledger.account.AccountRepository;
import com.vaibhav.ledger.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only class that writes to the ledger. Every call posts one balanced transaction:
 * its entries sum to zero, so money is never created or destroyed.
 */
@Service
public class LedgerService {

    private final LedgerRepository ledger;
    private final AccountRepository accounts;

    public LedgerService(LedgerRepository ledger, AccountRepository accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    @Transactional
    public LedgerTransaction post(TransactionType type, String description, List<Posting> postings) {
        validateShape(postings);

        // Lock first, then check every rule, then write.
        Map<UUID, Account> locked = lockAccounts(postings);
        String currency = null;
        for (Posting posting : postings) {
            Account account = locked.get(posting.accountId());
            if (!"ACTIVE".equals(account.status())) {
                throw ApiException.unprocessable("Account is not active: " + account.id());
            }
            if (currency == null) {
                currency = account.currency();
            } else if (!currency.equals(account.currency())) {
                throw ApiException.unprocessable("All accounts in a transaction must share one currency");
            }
            long newBalance = account.balance() + posting.direction().signed(posting.amount());
            if (!account.allowNegative() && newBalance < 0) {
                throw ApiException.unprocessable("Insufficient funds in account " + account.id());
            }
        }

        UUID transactionId = UUID.randomUUID();
        ledger.insertTransaction(transactionId, type, description);
        List<LedgerEntry> entries = new ArrayList<>();
        for (Posting posting : postings) {
            long balanceAfter = accounts.applyDelta(posting.accountId(), posting.direction().signed(posting.amount()));
            entries.add(ledger.insertEntry(transactionId, posting, balanceAfter));
        }
        return new LedgerTransaction(transactionId, type, "POSTED", description, entries);
    }

    /**
     * Locks every account in this transaction, always in ascending id order.
     * Because every transaction takes its locks in the same order, two transactions can never
     * each hold one lock while waiting for the other's, so they cannot deadlock.
     */
    private Map<UUID, Account> lockAccounts(List<Posting> postings) {
        List<UUID> ids = postings.stream().map(Posting::accountId).distinct().sorted().toList();
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID id : ids) {
            Account account = accounts.findByIdForUpdate(id)
                    .orElseThrow(() -> ApiException.notFound("Account not found: " + id));
            locked.put(id, account);
        }
        return locked;
    }

    private static void validateShape(List<Posting> postings) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("A transaction needs at least two entries");
        }
        long sum = 0;
        for (Posting posting : postings) {
            if (posting.amount() <= 0) {
                throw new IllegalArgumentException("Entry amounts must be positive");
            }
            sum = Math.addExact(sum, posting.direction().signed(posting.amount()));
        }
        if (sum != 0) {
            throw new IllegalArgumentException("Transaction is not balanced: entries sum to " + sum);
        }
    }
}