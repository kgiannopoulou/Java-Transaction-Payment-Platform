package com.kgiannopoulou.ledgerforge.shared.web;

import com.kgiannopoulou.ledgerforge.account.domain.AccountFrozenException;
import com.kgiannopoulou.ledgerforge.idempotency.domain.DuplicateIdempotencyKeyException;
import com.kgiannopoulou.ledgerforge.payment.domain.InvalidPaymentStateException;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentAlreadyCapturedException;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void unexpectedExceptionsBecomeGeneric500WithoutLeakingDetails() {
        MDC.put(TraceIds.MDC_KEY, "trace-1234");

        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("password=hunter2 at jdbc:postgresql://db/internal")).getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getType()).hasToString("INTERNAL_ERROR");
        assertThat(problem.getDetail()).doesNotContain("hunter2", "postgresql", "IllegalStateException");
        assertThat(problem.getProperties()).containsEntry("traceId", "trace-1234");
    }

    @Test
    void domainExceptionCategoriesMapToStatuses() {
        UUID id = UUID.randomUUID();
        assertThat(status(new AccountFrozenException(id))).isEqualTo(422);
        assertThat(status(new DuplicateIdempotencyKeyException("k"))).isEqualTo(409);
        assertThat(status(InvalidPaymentStateException.of(id, PaymentStatus.CREATED, PaymentStatus.CAPTURED)))
                .isEqualTo(409);
    }

    @Test
    void captureOfAnAlreadyCapturedPaymentGetsTheSpecificSubtype() {
        UUID id = UUID.randomUUID();
        for (PaymentStatus from : new PaymentStatus[] {
                PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED}) {
            assertThat(InvalidPaymentStateException.of(id, from, PaymentStatus.CAPTURED))
                    .isInstanceOf(PaymentAlreadyCapturedException.class);
        }
        assertThat(InvalidPaymentStateException.of(id, PaymentStatus.CANCELLED, PaymentStatus.CAPTURED))
                .isNotInstanceOf(PaymentAlreadyCapturedException.class);
    }

    private int status(com.kgiannopoulou.ledgerforge.shared.error.DomainException e) {
        return handler.handleDomain(e).getStatusCode().value();
    }
}
