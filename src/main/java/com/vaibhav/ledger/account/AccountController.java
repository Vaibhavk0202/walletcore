package com.vaibhav.ledger.account;

import com.vaibhav.ledger.ledger.LedgerEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    record OpenAccountRequest(
            @NotNull UUID ownerId,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Account open(@Valid @RequestBody OpenAccountRequest request) {
        return accountService.openWallet(request.ownerId(), request.currency());
    }

    @GetMapping("/{id}")
    Account get(@PathVariable UUID id) {
        return accountService.get(id);
    }

    @GetMapping("/{id}/entries")
    List<LedgerEntry> statement(
            @PathVariable UUID id,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "50") int limit) {
        return accountService.statement(id, beforeId, Math.clamp(limit, 1, 200));
    }
}