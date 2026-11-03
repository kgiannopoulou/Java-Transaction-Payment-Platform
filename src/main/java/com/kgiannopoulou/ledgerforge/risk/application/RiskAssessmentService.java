package com.kgiannopoulou.ledgerforge.risk.application;

import com.kgiannopoulou.ledgerforge.messaging.application.ProcessedEventLog;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskAlert;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskAlertRepository;
import com.kgiannopoulou.ledgerforge.shared.event.AccountFrozenEvent;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEvent;
import com.kgiannopoulou.ledgerforge.shared.event.PaymentCapturedEvent;
import com.kgiannopoulou.ledgerforge.shared.event.PaymentRefundedEvent;
import com.kgiannopoulou.ledgerforge.shared.event.TransferCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Downstream risk engine fed by Kafka. Deliberately simple (one large-amount rule): the point
 * is the consumer mechanics around it, i.e. idempotency, retries and the DLT.
 */
@Service
public class RiskAssessmentService {

    public static final String CONSUMER = "risk-engine";
    public static final String LARGE_TRANSFER = "LARGE_TRANSFER";
    public static final String LARGE_PAYMENT = "LARGE_PAYMENT";

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

    private final ProcessedEventLog processedEvents;
    private final RiskAlertRepository alerts;
    private final RiskProperties properties;
    private final Clock clock;

    public RiskAssessmentService(ProcessedEventLog processedEvents, RiskAlertRepository alerts,
                                 RiskProperties properties, Clock clock) {
        this.processedEvents = processedEvents;
        this.alerts = alerts;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void assess(IntegrationEvent event) {
        if (!processedEvents.firstDelivery(CONSUMER, event.eventId())) {
            return;
        }
        switch (event) {
            case TransferCompletedEvent t -> flagIfLarge(t.eventId(), LARGE_TRANSFER, t.sourceAccountId(),
                    "TRANSFER", t.transferId(), t.amount(), t.currency());
            case PaymentCapturedEvent p -> flagIfLarge(p.eventId(), LARGE_PAYMENT, p.payerAccountId(),
                    "PAYMENT", p.paymentId(), p.amount(), p.currency());
            case PaymentRefundedEvent ignored -> {
                // refunds return money to the payer; no rule yet
            }
            case AccountFrozenEvent ignored -> {
                // already acted upon by an operator
            }
        }
    }

    @Transactional(readOnly = true)
    public List<RiskAlert> recentAlerts(UUID accountId, int limit) {
        return alerts.findRecent(accountId, limit);
    }

    private void flagIfLarge(UUID eventId, String rule, UUID accountId, String referenceType, UUID referenceId,
                             BigDecimal amount, Currency currency) {
        if (amount.compareTo(properties.largeAmountThreshold()) < 0) {
            return;
        }
        alerts.save(new RiskAlert(UUID.randomUUID(), eventId, rule, accountId, referenceType, referenceId,
                amount, currency, clock.instant()));
        log.info("Risk alert {} for {} {} ({} {})", rule, referenceType, referenceId, amount, currency);
    }
}
