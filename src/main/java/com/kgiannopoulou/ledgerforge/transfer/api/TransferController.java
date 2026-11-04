package com.kgiannopoulou.ledgerforge.transfer.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.kgiannopoulou.ledgerforge.idempotency.api.IdempotencyHeaders;
import com.kgiannopoulou.ledgerforge.idempotency.application.IdempotentOperations;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import com.kgiannopoulou.ledgerforge.transfer.application.TransferMetrics;
import com.kgiannopoulou.ledgerforge.transfer.application.TransferMetrics.Outcome;
import com.kgiannopoulou.ledgerforge.transfer.application.TransferService;
import com.kgiannopoulou.ledgerforge.transfer.application.TransferService.TransferCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController {

    private final TransferService transferService;
    private final IdempotentOperations idempotent;
    private final TransferMetrics metrics;

    TransferController(TransferService transferService, IdempotentOperations idempotent, TransferMetrics metrics) {
        this.transferService = transferService;
        this.idempotent = idempotent;
        this.metrics = metrics;
    }

    /**
     * Books a transfer. Clients should send an {@code Idempotency-Key}; retries with the same key
     * and body return the original result (with {@code Idempotent-Replayed: true}) instead of
     * moving money twice.
     */
    @PostMapping
    @PreAuthorize("@access.ownsAccount(#request.sourceAccountId())")
    ResponseEntity<TransferResponse> create(
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        var command = new TransferCommand(request.sourceAccountId(), request.destinationAccountId(),
                Money.of(request.amount(), request.currency()));
        long start = System.nanoTime();
        Outcome outcome = Outcome.FAILED;
        try {
            var result = idempotent.execute("transfer", idempotencyKey, request, TransferResponse.class,
                    HttpStatus.CREATED.value(), () -> TransferResponse.from(transferService.transfer(command)));
            outcome = result.replayed() ? Outcome.REPLAYED : Outcome.COMPLETED;
            return IdempotencyHeaders.respond(result);
        } finally {
            metrics.record(outcome, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.canReadTransfer(#id)")
    TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.from(transferService.get(id));
    }
}
