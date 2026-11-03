package com.kgiannopoulou.ledgerforge.ledger.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.kgiannopoulou.ledgerforge.account.application.AccountService;
import com.kgiannopoulou.ledgerforge.idempotency.application.IdempotentOperations;
import com.kgiannopoulou.ledgerforge.ledger.application.DepositService;
import com.kgiannopoulou.ledgerforge.ledger.application.LedgerReconciliationService;
import com.kgiannopoulou.ledgerforge.ledger.application.LedgerService;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerQueries;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import com.kgiannopoulou.ledgerforge.idempotency.api.IdempotencyHeaders;
import com.kgiannopoulou.ledgerforge.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class LedgerController {

    private final DepositService depositService;
    private final LedgerService ledgerService;
    private final LedgerQueries ledgerQueries;
    private final AccountService accountService;
    private final LedgerReconciliationService reconciliation;
    private final IdempotentOperations idempotent;

    LedgerController(DepositService depositService, LedgerService ledgerService, LedgerQueries ledgerQueries,
                     AccountService accountService, LedgerReconciliationService reconciliation,
                     IdempotentOperations idempotent) {
        this.depositService = depositService;
        this.ledgerService = ledgerService;
        this.ledgerQueries = ledgerQueries;
        this.accountService = accountService;
        this.reconciliation = reconciliation;
        this.idempotent = idempotent;
    }

    @PostMapping("/api/v1/accounts/{accountId}/deposits")
    ResponseEntity<DepositResponse> deposit(
            @PathVariable UUID accountId,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        Money amount = Money.of(request.amount(), request.currency());
        var result = idempotent.execute("deposit", idempotencyKey, new DepositFingerprint(accountId, request),
                DepositResponse.class, HttpStatus.CREATED.value(),
                () -> DepositResponse.from(depositService.deposit(accountId, amount)));
        return IdempotencyHeaders.respond(result);
    }

    @GetMapping("/api/v1/accounts/{accountId}/transactions")
    @PreAuthorize("@access.canReadAccount(#accountId)")
    @Transactional(readOnly = true)
    PageResponse<AccountEntryResponse> accountTransactions(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        accountService.get(accountId); // 404 for unknown accounts instead of an empty page
        var entries = ledgerQueries.findEntriesByAccount(accountId, PageRequest.of(page, size));
        return PageResponse.from(entries.map(AccountEntryResponse::from));
    }

    @GetMapping("/api/v1/ledger/transactions/{id}")
    LedgerTransactionResponse transaction(@PathVariable UUID id) {
        return LedgerTransactionResponse.from(ledgerService.get(id));
    }

    /** Operator endpoint (AUDITOR, ADMIN; enforced in the security config). */
    @GetMapping("/api/v1/admin/reconciliation")
    LedgerReconciliationService.Report reconcile() {
        return reconciliation.reconcile();
    }

    /** What makes two deposit requests "the same request" for idempotency purposes. */
    record DepositFingerprint(UUID accountId, DepositRequest request) {
    }
}
