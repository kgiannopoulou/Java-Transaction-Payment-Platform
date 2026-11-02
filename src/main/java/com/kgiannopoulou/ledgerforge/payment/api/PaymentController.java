package com.kgiannopoulou.ledgerforge.payment.api;

import com.kgiannopoulou.ledgerforge.idempotency.api.IdempotencyHeaders;
import com.kgiannopoulou.ledgerforge.idempotency.application.IdempotentOperations;
import com.kgiannopoulou.ledgerforge.payment.application.PaymentService;
import com.kgiannopoulou.ledgerforge.payment.application.PaymentService.CreatePaymentCommand;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.AuthorizePayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.CancelPayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.CapturePayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.RefundPayment;
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

    PaymentController(PaymentService paymentService, IdempotentOperations idempotent) {
        this.paymentService = paymentService;
        this.idempotent = idempotent;
    }

    @PostMapping
    ResponseEntity<PaymentResponse> create(
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        var command = new CreatePaymentCommand(request.payerAccountId(), request.merchantAccountId(),
                Money.of(request.amount(), request.currency()), request.description());
        var result = idempotent.execute("payment.create", idempotencyKey, request, PaymentResponse.class,
                HttpStatus.CREATED.value(), () -> PaymentResponse.from(paymentService.create(command)));
        return IdempotencyHeaders.respond(result);
    }

    @GetMapping("/{id}")
    PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(paymentService.get(id));
    }

    @PostMapping("/{id}/authorize")
    ResponseEntity<PaymentResponse> authorize(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey) {
        return handle("payment.authorize", idempotencyKey, new AuthorizePayment(id));
    }

    @PostMapping("/{id}/capture")
    ResponseEntity<PaymentResponse> capture(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey) {
        return handle("payment.capture", idempotencyKey, new CapturePayment(id));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<PaymentResponse> cancel(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey) {
        return handle("payment.cancel", idempotencyKey, new CancelPayment(id));
    }

    @PostMapping("/{id}/refund")
    ResponseEntity<PaymentResponse> refund(
            @PathVariable UUID id,
            @RequestHeader(name = IdempotencyHeaders.KEY, required = false) String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {
        return handle("payment.refund", idempotencyKey,
                new RefundPayment(id, Money.of(request.amount(), request.currency())));
    }

    /** The command record itself (payment id + amount) is the idempotency fingerprint. */
    private ResponseEntity<PaymentResponse> handle(String scope, String idempotencyKey, PaymentCommand command) {
        var result = idempotent.execute(scope, idempotencyKey, command, PaymentResponse.class,
                HttpStatus.OK.value(), () -> PaymentResponse.from(paymentService.handle(command)));
        return IdempotencyHeaders.respond(result);
    }
}
