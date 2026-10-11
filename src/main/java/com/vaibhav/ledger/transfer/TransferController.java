package com.vaibhav.ledger.transfer;

import com.vaibhav.ledger.idempotency.IdempotencyService;
import com.vaibhav.ledger.idempotency.IdempotentResult;
import com.vaibhav.ledger.ledger.LedgerTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class TransferController {

    private final TransferService transferService;
    private final IdempotencyService idempotency;

    TransferController(TransferService transferService, IdempotencyService idempotency) {
        this.transferService = transferService;
        this.idempotency = idempotency;
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
    ResponseEntity<LedgerTransaction> deposit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        IdempotentResult<LedgerTransaction> result = idempotency.execute(
                idempotencyKey, "POST /api/v1/deposits", request, LedgerTransaction.class,
                () -> transferService.deposit(request.accountId(), request.amount(), request.description()));
        return created(result);
    }

    @PostMapping("/transfers")
    ResponseEntity<LedgerTransaction> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        IdempotentResult<LedgerTransaction> result = idempotency.execute(
                idempotencyKey, "POST /api/v1/transfers", request, LedgerTransaction.class,
                () -> transferService.transfer(
                        request.fromAccountId(), request.toAccountId(), request.amount(), request.description()));
        return created(result);
    }

    /** Same status and body for the first call and for every replay; a header tells them apart. */
    private static ResponseEntity<LedgerTransaction> created(IdempotentResult<LedgerTransaction> result) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.body());
    }
}