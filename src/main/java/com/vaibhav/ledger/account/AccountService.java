package com.vaibhav.ledger.account;

import com.vaibhav.ledger.common.ApiException;
import com.vaibhav.ledger.ledger.LedgerEntry;
import com.vaibhav.ledger.ledger.LedgerRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public AccountService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional
    public Account openWallet(UUID ownerId, String currency) {
        SystemAccounts.settlementFor(currency); // rejects currencies we cannot deposit in yet
        UUID id = UUID.randomUUID();
        accounts.insert(id, ownerId, AccountType.USER_WALLET, currency);
        return get(id);
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Account not found: " + id));
    }

    /** Newest entries first. Pass the last id you received as beforeId to get the next page. */
    @Transactional(readOnly = true)
    public List<LedgerEntry> statement(UUID accountId, Long beforeId, int limit) {
        get(accountId);
        return ledger.findEntries(accountId, beforeId, limit);
    }
}