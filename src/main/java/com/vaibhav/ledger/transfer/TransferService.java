package com.vaibhav.ledger.transfer;

import com.vaibhav.ledger.account.Account;
import com.vaibhav.ledger.account.AccountService;
import com.vaibhav.ledger.account.AccountType;
import com.vaibhav.ledger.account.SystemAccounts;
import com.vaibhav.ledger.common.ApiException;
import com.vaibhav.ledger.ledger.LedgerService;
import com.vaibhav.ledger.ledger.LedgerTransaction;
import com.vaibhav.ledger.ledger.Posting;
import com.vaibhav.ledger.ledger.TransactionType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountService accountService;
    private final LedgerService ledgerService;

    public TransferService(AccountService accountService, LedgerService ledgerService) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
    }

    /** Money enters from the settlement account: DEBIT settlement, CREDIT the wallet. */
    @Transactional
    public LedgerTransaction deposit(UUID accountId, long amount, String description) {
        Account wallet = accountService.get(accountId);
        if (wallet.type() != AccountType.USER_WALLET) {
            throw ApiException.unprocessable("Deposits go into user wallets only");
        }
        UUID settlement = SystemAccounts.settlementFor(wallet.currency());
        return ledgerService.post(TransactionType.DEPOSIT, description, List.of(
                Posting.debit(settlement, amount),
                Posting.credit(accountId, amount)));
    }

    /** DEBIT the sender, CREDIT the receiver. */
    @Transactional
    public LedgerTransaction transfer(UUID fromAccountId, UUID toAccountId, long amount, String description) {
        if (fromAccountId.equals(toAccountId)) {
            throw ApiException.unprocessable("Cannot transfer to the same account");
        }
        return ledgerService.post(TransactionType.TRANSFER, description, List.of(
                Posting.debit(fromAccountId, amount),
                Posting.credit(toAccountId, amount)));
    }
}