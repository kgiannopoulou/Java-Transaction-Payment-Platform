package com.kgiannopoulou.ledgerforge.payment.application;

import com.kgiannopoulou.ledgerforge.shared.event.PaymentRefundedEvent;
import com.kgiannopoulou.ledgerforge.shared.event.PaymentCapturedEvent;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEventPublisher;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditedEntity;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditAction;
import com.kgiannopoulou.ledgerforge.audit.application.AuditTrail;
import com.kgiannopoulou.ledgerforge.account.application.AccountLocks;
import com.kgiannopoulou.ledgerforge.account.application.AccountService;
import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.account.domain.AccountType;
import com.kgiannopoulou.ledgerforge.account.domain.InsufficientFundsException;
import com.kgiannopoulou.ledgerforge.ledger.application.LedgerService;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransactionType;
import com.kgiannopoulou.ledgerforge.ledger.domain.Posting;
import com.kgiannopoulou.ledgerforge.account.domain.AccountStatus;
import com.kgiannopoulou.ledgerforge.payment.domain.Payment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentBlockedException;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.AuthorizePayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.CancelPayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.CapturePayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.RefundPayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.ReviewPayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentRepository;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus;
import com.kgiannopoulou.ledgerforge.risk.application.PaymentScreening;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecision;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskOutcome;
import com.kgiannopoulou.ledgerforge.risk.domain.ScreenedPayment;
import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.error.NotFoundException;
import com.kgiannopoulou.ledgerforge.shared.money.CurrencyMismatchException;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the payment state machine and books the matching ledger movements:
 *
 * <pre>
 *   authorize  payer wallet   DEBIT  / PAYMENT_HOLDS  CREDIT
 *   capture    PAYMENT_HOLDS  DEBIT  / merchant       CREDIT
 *   cancel     PAYMENT_HOLDS  DEBIT  / payer wallet   CREDIT   (only if it was authorized)
 *   refund     merchant       DEBIT  / payer wallet   CREDIT
 * </pre>
 *
 * Lock order is always: payment row first, then accounts in ascending id order.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final AccountService accountService;
    private final AccountLocks accountLocks;
    private final LedgerService ledgerService;
    private final AuditTrail audit;
    private final IntegrationEventPublisher events;
    private final PaymentScreening screening;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, AccountService accountService, AccountLocks accountLocks,
                          LedgerService ledgerService, AuditTrail audit, IntegrationEventPublisher events,
                          PaymentScreening screening, Clock clock) {
        this.payments = payments;
        this.accountService = accountService;
        this.accountLocks = accountLocks;
        this.ledgerService = ledgerService;
        this.audit = audit;
        this.events = events;
        this.screening = screening;
        this.clock = clock;
    }

    public record CreatePaymentCommand(UUID payerAccountId, UUID merchantAccountId, Money amount,
                                       String description) {
    }

    @Transactional
    public Payment create(CreatePaymentCommand command) {
        Payment payment = Payment.create(command.payerAccountId(), command.merchantAccountId(), command.amount(),
                command.description(), clock.instant());
        // No money moves on creation, so plain reads are enough here.
        Account payer = requireWalletIn(command.payerAccountId(), command.amount());
        Account merchant = requireWalletIn(command.merchantAccountId(), command.amount());
        merchant.requireActive();
        if (payer.getStatus() != AccountStatus.FROZEN) {
            payer.requireActive(); // a frozen payer is the risk engine's call, so the refusal is recorded
        }

        ScreenedPayment screened = screening.screen(payer.getId(), merchant.getId(), command.amount(),
                payer.getStatus() == AccountStatus.FROZEN, payer.getCreatedAt());
        if (screened.outcome() == RiskOutcome.BLOCK) {
            throw new PaymentBlockedException(screened);
        }
        if (screened.outcome() == RiskOutcome.REVIEW) {
            payment.holdForReview(clock.instant());
        }

        // Flushed first: JPA would otherwise delay this INSERT until after the decision that references it.
        Payment saved = payments.saveAndFlush(payment);
        screening.record(saved.getId(), screened);
        audit.record(AuditAction.PAYMENT_CREATED, AuditedEntity.PAYMENT, saved.getId(), Map.of(
                "payerAccountId", saved.getPayerAccountId().toString(),
                "merchantAccountId", saved.getMerchantAccountId().toString(),
                "amount", saved.getAmount().amount().toPlainString(),
                "currency", saved.getCurrency().getCurrencyCode(),
                "riskOutcome", screened.outcome().name()));
        return saved;
    }

    /** Records a refusal. Called after the request's transaction has rolled back. */
    @Transactional
    public void recordBlocked(ScreenedPayment screened) {
        screening.recordBlocked(screened);
        audit.record(AuditAction.PAYMENT_BLOCKED, AuditedEntity.ACCOUNT, screened.payerAccountId(), Map.of(
                "merchantAccountId", screened.merchantAccountId().toString(),
                "amount", screened.amount().amount().toPlainString(),
                "currency", screened.amount().currency().getCurrencyCode(),
                "rules", screened.assessment().triggered().stream().map(RiskDecision::rule).toList()));
    }

    @Transactional
    public Payment handle(PaymentCommand command) {
        Payment payment = payments.findByIdForUpdate(command.paymentId())
                .orElseThrow(() -> new NotFoundException("Payment", command.paymentId()));

        // Exhaustive: no default branch, so adding a new PaymentCommand is a compile error here.
        switch (command) {
            case AuthorizePayment ignored -> authorize(payment);
            case CapturePayment ignored -> capture(payment);
            case CancelPayment ignored -> cancel(payment);
            case RefundPayment refund -> refund(payment, refund.amount());
            case ReviewPayment review -> review(payment, review);
        }
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return payments.findById(id).orElseThrow(() -> new NotFoundException("Payment", id));
    }

    private void authorize(Payment payment) {
        payment.requireTransition(PaymentStatus.AUTHORIZED);
        UUID holds = holdsAccountId(payment);
        Map<UUID, Account> locked = accountLocks.lockForBalanceChange(List.of(payment.getPayerAccountId(), holds));
        Account payer = locked.get(payment.getPayerAccountId());
        payer.requireActive();
        if (!payer.canCover(payment.getAmount())) {
            throw new InsufficientFundsException(payer.getId());
        }
        ledgerService.post(payment.authorizationReference(), LedgerTransactionType.PAYMENT_AUTHORIZATION,
                List.of(Posting.debit(payer.getId(), payment.getAmount()), Posting.credit(holds, payment.getAmount())),
                locked);
        payment.markAuthorized(clock.instant());
        auditPayment(payment, AuditAction.PAYMENT_AUTHORIZED, payment.getAmount());
    }

    private void capture(Payment payment) {
        payment.requireTransition(PaymentStatus.CAPTURED);
        UUID holds = holdsAccountId(payment);
        Map<UUID, Account> locked = accountLocks.lockForBalanceChange(List.of(holds, payment.getMerchantAccountId()));
        locked.get(payment.getMerchantAccountId()).requireActive();
        ledgerService.post(payment.captureReference(), LedgerTransactionType.PAYMENT_CAPTURE,
                List.of(Posting.debit(holds, payment.getAmount()),
                        Posting.credit(payment.getMerchantAccountId(), payment.getAmount())),
                locked);
        payment.markCaptured(clock.instant());
        auditPayment(payment, AuditAction.PAYMENT_CAPTURED, payment.getAmount());
        events.publish(new PaymentCapturedEvent(UUID.randomUUID(), payment.getId(), payment.getPayerAccountId(),
                payment.getMerchantAccountId(), payment.getAmount().amount(), payment.getCurrency(), clock.instant()));
    }

    private void cancel(Payment payment) {
        payment.requireTransition(PaymentStatus.CANCELLED);
        if (payment.getStatus() == PaymentStatus.AUTHORIZED) {
            // Releasing a hold is a credit to the payer, so it is allowed even if the payer is frozen.
            UUID holds = holdsAccountId(payment);
            Map<UUID, Account> locked = accountLocks.lockForBalanceChange(List.of(holds, payment.getPayerAccountId()));
            ledgerService.post(payment.cancellationReference(), LedgerTransactionType.PAYMENT_CANCELLATION,
                    List.of(Posting.debit(holds, payment.getAmount()),
                            Posting.credit(payment.getPayerAccountId(), payment.getAmount())),
                    locked);
        }
        payment.markCancelled(clock.instant());
        auditPayment(payment, AuditAction.PAYMENT_CANCELLED, payment.getAmount());
    }

    private void refund(Payment payment, Money amount) {
        payment.requireRefundable(amount);
        Map<UUID, Account> locked = accountLocks.lockForBalanceChange(
                List.of(payment.getMerchantAccountId(), payment.getPayerAccountId()));
        Account merchant = locked.get(payment.getMerchantAccountId());
        if (!merchant.canCover(amount)) {
            throw new InsufficientFundsException(merchant.getId());
        }
        ledgerService.post(payment.refundReference(UUID.randomUUID()), LedgerTransactionType.PAYMENT_REFUND,
                List.of(Posting.debit(merchant.getId(), amount), Posting.credit(payment.getPayerAccountId(), amount)),
                locked);
        payment.recordRefund(amount, clock.instant());
        auditPayment(payment, AuditAction.PAYMENT_REFUNDED, amount);
        events.publish(new PaymentRefundedEvent(UUID.randomUUID(), payment.getId(), payment.getPayerAccountId(),
                payment.getMerchantAccountId(), amount.amount(), amount.currency(),
                payment.getStatus() == PaymentStatus.REFUNDED, clock.instant()));
    }

    private void review(Payment payment, ReviewPayment review) {
        if (review.approved()) {
            payment.approveReview(clock.instant());
        } else {
            payment.rejectReview(clock.instant());
        }
        audit.record(review.approved() ? AuditAction.PAYMENT_REVIEW_APPROVED : AuditAction.PAYMENT_REVIEW_REJECTED,
                AuditedEntity.PAYMENT, payment.getId(), Map.of(
                        "status", payment.getStatus().name(),
                        "note", review.note() == null ? "" : review.note()));
    }

    private void auditPayment(Payment payment, AuditAction action, Money amount) {
        audit.record(action, AuditedEntity.PAYMENT, payment.getId(), Map.of(
                "status", payment.getStatus().name(),
                "amount", amount.amount().toPlainString(),
                "currency", amount.currency().getCurrencyCode()));
    }

    private UUID holdsAccountId(Payment payment) {
        return accountService.systemAccountId(AccountType.PAYMENT_HOLDS, payment.getCurrency());
    }

    private Account requireWalletIn(UUID accountId, Money amount) {
        Account account = accountService.get(accountId);
        if (account.getType() != AccountType.CUSTOMER_WALLET) {
            throw new BusinessRuleViolationException("NOT_A_WALLET", "Payments are only allowed between customer wallets");
        }
        if (!account.getCurrency().equals(amount.currency())) {
            throw new CurrencyMismatchException(account.getCurrency(), amount.currency());
        }
        return account;
    }
}
