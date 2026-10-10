package com.vaibhav.ledger.transfer;

import com.vaibhav.ledger.ledger.LedgerTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class TransferController {

    private final TransferService transferService;

    TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    record DepositRequest(
            @NotNull UUID accountId,
            @NotNull @Positive Long amount,
            @Size(max = 255) String description) {
    }

    record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull @Positive Long amount,
            @Size(max = 255) String description) {
    }

    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    LedgerTransaction deposit(@Valid @RequestBody DepositRequest request) {
        return transferService.deposit(request.accountId(), request.amount(), request.description());
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    LedgerTransaction transfer(@Valid @RequestBody TransferRequest request) {
        return transferService.transfer(
                request.fromAccountId(), request.toAccountId(), request.amount(), request.description());
    }
}