package com.kgiannopoulou.ledgerforge.security.application;

import com.kgiannopoulou.ledgerforge.account.domain.AccountRepository;
import com.kgiannopoulou.ledgerforge.payment.domain.Payment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentRepository;
import com.kgiannopoulou.ledgerforge.security.domain.Role;
import com.kgiannopoulou.ledgerforge.transfer.domain.TransferRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Resource-level authorization, used from {@code @PreAuthorize("@access....")} on controllers.
 *
 * <p>Customers may only touch resources they own; for anything else, including ids that do
 * not exist, the answer is "no" (403). That way the response does not reveal whether another
 * customer's account exists. Staff roles get 404 for unknown ids as usual, because they are
 * allowed to know.
 *
 * <p>Runs before the business transaction, in its own read-only one. It only reads ownership,
 * which never changes after creation, so there is no time-of-check/time-of-use gap.
 */
@Component("access")
@Transactional(readOnly = true)
public class AccessPolicy {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final PaymentRepository payments;

    public AccessPolicy(AccountRepository accounts, TransferRepository transfers, PaymentRepository payments) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.payments = payments;
    }

    // ---- customers ---------------------------------------------------------------------------

    public boolean canReadCustomer(UUID customerId) {
        return CurrentPrincipal.hasAnyRole(Role.SUPPORT, Role.ADMIN) || isCaller(customerId);
    }

    public boolean canOpenAccountFor(UUID customerId) {
        return CurrentPrincipal.hasAnyRole(Role.ADMIN) || isCaller(customerId);
    }

    // ---- accounts ----------------------------------------------------------------------------

    public boolean canReadAccount(UUID accountId) {
        return CurrentPrincipal.hasAnyRole(Role.SUPPORT, Role.AUDITOR, Role.ADMIN) || ownsAccount(accountId);
    }

    /** Strict ownership: required to move money out of an account. Staff roles never pass. */
    public boolean ownsAccount(UUID accountId) {
        return CurrentPrincipal.customerId()
                .map(caller -> accounts.findCustomerIdById(accountId).map(caller::equals).orElse(false))
                .orElse(false);
    }

    // ---- transfers ---------------------------------------------------------------------------

    public boolean canReadTransfer(UUID transferId) {
        if (CurrentPrincipal.hasAnyRole(Role.SUPPORT, Role.AUDITOR, Role.ADMIN)) {
            return true;
        }
        return transfers.findById(transferId)
                .map(t -> ownsAccount(t.getSourceAccountId()) || ownsAccount(t.getDestinationAccountId()))
                .orElse(false);
    }

    // ---- payments ----------------------------------------------------------------------------

    public boolean canReadPayment(UUID paymentId) {
        return CurrentPrincipal.hasAnyRole(Role.SUPPORT, Role.AUDITOR, Role.ADMIN) || isPaymentParticipant(paymentId);
    }

    /** Authorize: the payer approves the hold on their own funds. */
    public boolean isPayer(UUID paymentId) {
        return paymentAccount(paymentId, Payment::getPayerAccountId).map(this::ownsAccount).orElse(false);
    }

    /** Capture and refund: the merchant collects, or gives back, the money. */
    public boolean isMerchant(UUID paymentId) {
        return paymentAccount(paymentId, Payment::getMerchantAccountId).map(this::ownsAccount).orElse(false);
    }

    /** Cancel: either side may call off a payment that has not been captured. */
    public boolean isPaymentParticipant(UUID paymentId) {
        return isPayer(paymentId) || isMerchant(paymentId);
    }

    private Optional<UUID> paymentAccount(UUID paymentId, Function<Payment, UUID> side) {
        if (CurrentPrincipal.customerId().isEmpty()) {
            return Optional.empty();
        }
        return payments.findById(paymentId).map(side);
    }

    private static boolean isCaller(UUID customerId) {
        return CurrentPrincipal.customerId().map(customerId::equals).orElse(false);
    }
}
