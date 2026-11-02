package com.kgiannopoulou.ledgerforge.payment.domain;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.money.CurrencyMismatchException;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import com.kgiannopoulou.ledgerforge.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * A payment from a payer wallet to a merchant wallet, driven through {@link PaymentStatus}.
 * The entity guards its own state machine; the money movements for each step are booked by
 * {@code PaymentService} in the same transaction.
 */
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Column(name = "payer_account_id", nullable = false, updatable = false)
    private UUID payerAccountId;

    @Column(name = "merchant_account_id", nullable = false, updatable = false)
    private UUID merchantAccountId;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount;

    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(length = 255)
    private String description;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    private Payment(UUID id, UUID payerAccountId, UUID merchantAccountId, Money amount, String description,
                    Instant now) {
        super(id);
        this.payerAccountId = Objects.requireNonNull(payerAccountId);
        this.merchantAccountId = Objects.requireNonNull(merchantAccountId);
        this.amount = amount.amount();
        this.refundedAmount = Money.zero(amount.currency()).amount();
        this.currency = amount.currency();
        this.status = PaymentStatus.CREATED;
        this.description = description;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Payment create(UUID payerAccountId, UUID merchantAccountId, Money amount, String description,
                                 Instant now) {
        if (!amount.isPositive()) {
            throw new BusinessRuleViolationException("invalid_amount", "Payment amount must be positive");
        }
        if (payerAccountId.equals(merchantAccountId)) {
            throw new BusinessRuleViolationException("same_account", "Payer and merchant accounts must differ");
        }
        return new Payment(UUID.randomUUID(), payerAccountId, merchantAccountId, amount, description, now);
    }

    /** Throws unless {@code target} is reachable from the current status. Call BEFORE moving money. */
    public void requireTransition(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidPaymentTransitionException(getId(), status, target);
        }
    }

    public void markAuthorized(Instant now) {
        transition(PaymentStatus.AUTHORIZED, now);
    }

    public void markCaptured(Instant now) {
        transition(PaymentStatus.CAPTURED, now);
    }

    public void markCancelled(Instant now) {
        transition(PaymentStatus.CANCELLED, now);
    }

    /** Validates a refund against the remaining refundable amount without changing state. */
    public void requireRefundable(Money refund) {
        if (!refund.currency().equals(currency)) {
            throw new CurrencyMismatchException(currency, refund.currency());
        }
        if (!refund.isPositive()) {
            throw new BusinessRuleViolationException("invalid_amount", "Refund amount must be positive");
        }
        requireTransition(nextStatusAfterRefund(refund)); // only CAPTURED / PARTIALLY_REFUNDED pass
        if (refund.isGreaterThan(getRefundableAmount())) {
            throw new BusinessRuleViolationException("refund_exceeds_captured",
                    "Refund " + refund + " exceeds refundable amount " + getRefundableAmount());
        }
    }

    public void recordRefund(Money refund, Instant now) {
        requireRefundable(refund);
        PaymentStatus next = nextStatusAfterRefund(refund);
        refundedAmount = getRefundedAmount().add(refund).amount();
        transition(next, now);
    }

    private PaymentStatus nextStatusAfterRefund(Money refund) {
        return getRefundedAmount().add(refund).compareTo(getAmount()) >= 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
    }

    private void transition(PaymentStatus target, Instant now) {
        requireTransition(target);
        this.status = target;
        this.updatedAt = now;
    }

    // ---- ledger references: each money movement of a payment can be booked only once -------

    public String authorizationReference() {
        return "PAY-" + getId() + "-AUTH";
    }

    public String captureReference() {
        return "PAY-" + getId() + "-CAPTURE";
    }

    public String cancellationReference() {
        return "PAY-" + getId() + "-CANCEL";
    }

    public String refundReference(UUID refundId) {
        return "PAY-" + getId() + "-REFUND-" + refundId;
    }

    // ---- accessors ---------------------------------------------------------------------------

    public UUID getPayerAccountId() {
        return payerAccountId;
    }

    public UUID getMerchantAccountId() {
        return merchantAccountId;
    }

    public Money getAmount() {
        return Money.of(amount, currency);
    }

    public Money getRefundedAmount() {
        return Money.of(refundedAmount, currency);
    }

    public Money getRefundableAmount() {
        return getAmount().subtract(getRefundedAmount());
    }

    public Currency getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
