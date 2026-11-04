package com.kgiannopoulou.ledgerforge.payment.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.kgiannopoulou.ledgerforge.idempotency.api.IdempotencyHeaders;
import com.kgiannopoulou.ledgerforge.idempotency.application.IdempotentOperations;
import com.kgiannopoulou.ledgerforge.payment.application.PaymentMetrics;
import com.kgiannopoulou.ledgerforge.payment.application.PaymentService;
import com.kgiannopoulou.ledgerforge.payment.application.PaymentService.CreatePaymentCommand;
import com.kgiannopoulou.ledgerforge.account.domain.InsufficientFundsException;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentBlockedException;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.AuthorizePayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.CancelPayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.CapturePayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.RefundPayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.ReviewPayment;
import com.kgiannopoulou.ledgerforge.ratelimit.application.RateLimiter;
import com.kgiannopoulou.ledgerforge.risk.application.RiskSignals;
import com.kgiannopoulou.ledgerforge.shared.error.DomainException;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {

    private final PaymentService paymentService;
    private final IdempotentOperations idempotent;
    private final RateLimiter rateLimiter;
    private final RiskSignals riskSignals;
    private final PaymentMetrics metrics;

    PaymentController(PaymentService paymentService, IdempotentOperations idempotent, RateLimiter rateLimiter,
                      RiskSignals riskSignals, PaymentMetrics metrics) {
        this.paymentService = paymentService;
        this.idempotent = idempotent;
        this.rateLimiter = rateLimiter;
        this.riskSignals = riskSignals;
        this.metrics = metrics;
    }

    @PostMapping
    @PreAuthorize("@access.ownsAccount(#request.payerAccountId())")
    ResponseEntity<PaymentResponse> create(
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        // After @PreAuthorize, so a caller can only spend the allowance of an account they own.
        rateLimiter.checkPaymentCreation(request.payerAccountId());
        var command = new CreatePaymentCommand(request.payerAccountId(), request.merchantAccountId(),
                Money.of(request.amount(), request.currency()), request.description());
        try {
            var result = idempotent.execute("payment.create", idempotencyKey, request, PaymentResponse.class,
                    HttpStatus.CREATED.value(), () -> PaymentResponse.from(paymentService.create(command)));
            if (!result.replayed()) {
                metrics.created();
            }
            return IdempotencyHeaders.respond(result);
        } catch (PaymentBlockedException e) {
            // Here rather than in the service: the transaction has rolled back by now, so the
            // record of the refusal survives. No database locks or connections are held either.
            paymentService.recordBlocked(e.screened());
            metrics.failed("create", e.type());
            throw e;
        } catch (RuntimeException e) {
            metrics.failed("create", failureReason(e));
            throw e;
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.canReadPayment(#id)")
    PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(paymentService.get(id));
    }

    @PostMapping("/{id}/authorize")
    @PreAuthorize("@access.isPayer(#id)")
    ResponseEntity<PaymentResponse> authorize(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey) {
        try {
            return handle("payment.authorize", idempotencyKey, new AuthorizePayment(id));
        } catch (InsufficientFundsException e) {
            riskSignals.recordFailure(e.accountId()); // after rollback, with no account locks held
            throw e;
        }
    }

    @PostMapping("/{id}/capture")
    @PreAuthorize("@access.isMerchant(#id)")
    ResponseEntity<PaymentResponse> capture(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey) {
        return handle("payment.capture", idempotencyKey, new CapturePayment(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@access.isPaymentParticipant(#id)")
    ResponseEntity<PaymentResponse> cancel(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey) {
        return handle("payment.cancel", idempotencyKey, new CancelPayment(id));
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("@access.isMerchant(#id)")
    ResponseEntity<PaymentResponse> refund(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {
        return handle("payment.refund", idempotencyKey,
                new RefundPayment(id, Money.of(request.amount(), request.currency())));
    }

    /** SUPPORT and ADMIN only (see SecurityConfiguration). */
    @PostMapping("/{id}/review")
    ResponseEntity<PaymentResponse> review(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ReviewRequest request) {
        return handle("payment.review", idempotencyKey,
                new ReviewPayment(id, request.decision() == ReviewRequest.Decision.APPROVE, request.note()));
    }

    /** The command record itself (payment id + amount) is the idempotency fingerprint. */
    private ResponseEntity<PaymentResponse> handle(String scope, String idempotencyKey, PaymentCommand command) {
        try {
            var result = idempotent.execute(scope, idempotencyKey, command, PaymentResponse.class,
                    HttpStatus.OK.value(), () -> PaymentResponse.from(paymentService.handle(command)));
            return IdempotencyHeaders.respond(result);
        } catch (RuntimeException e) {
            metrics.failed(scope.substring(scope.indexOf('.') + 1), failureReason(e));
            throw e;
        }
    }

    private static String failureReason(RuntimeException e) {
        return e instanceof DomainException domain ? domain.type() : "INTERNAL_ERROR";
    }
}
