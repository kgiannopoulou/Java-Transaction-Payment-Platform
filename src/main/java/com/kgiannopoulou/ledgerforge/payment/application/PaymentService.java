package com.kgiannopoulou.ledgerforge.payment.application;

import com.kgiannopoulou.ledgerforge.account.application.AccountLocks;
import com.kgiannopoulou.ledgerforge.account.application.AccountService;
import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.account.domain.AccountType;
import com.kgiannopoulou.ledgerforge.account.domain.InsufficientFundsException;
import com.kgiannopoulou.ledgerforge.ledger.application.LedgerService;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransactionType;
import com.kgiannopoulou.ledgerforge.ledger.domain.Posting;
import com.kgiannopoulou.ledgerforge.payment.domain.Payment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.AuthorizePayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.CancelPayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.CapturePayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentCommand.RefundPayment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentRepository;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus;
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
    private final Clock clock;

    public PaymentService(PaymentRepository payments, AccountService accountService, AccountLocks accountLocks,
                          LedgerService ledgerService, Clock clock) {
        this.payments = payments;
        this.accountService = accountService;
        this.accountLocks = accountLocks;
        this.ledgerService = ledgerService;
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
        for (UUID accountId : List.of(command.payerAccountId(), command.merchantAccountId())) {
            Account account = accountService.get(accountId);
            requireWallet(account);
            if (!account.getCurrency().equals(command.amount().currency())) {
                throw new CurrencyMismatchException(account.getCurrency(), command.amount().currency());
            }
        }
        return payments.save(payment);
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
    }

    private UUID holdsAccountId(Payment payment) {
        return accountService.systemAccountId(AccountType.PAYMENT_HOLDS, payment.getCurrency());
    }

    private static void requireWallet(Account account) {
        if (account.getType() != AccountType.CUSTOMER_WALLET) {
            throw new BusinessRuleViolationException("NOT_A_WALLET", "Payments are only allowed between customer wallets");
        }
        account.requireActive();
    }
}
