package com.kgiannopoulou.ledgerforge.ledger.application;

import com.kgiannopoulou.ledgerforge.account.application.AccountLocks;
import com.kgiannopoulou.ledgerforge.account.application.AccountService;
import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.account.domain.AccountType;
import com.kgiannopoulou.ledgerforge.account.domain.SupportedCurrencies;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransaction;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransactionType;
import com.kgiannopoulou.ledgerforge.ledger.domain.Posting;
import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.money.CurrencyMismatchException;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Brings external money into a wallet: DEBIT the currency's EXTERNAL_FUNDING account,
 * CREDIT the wallet. (In a real system this would be triggered by a settled card top-up or
 * bank transfer; here it is an API call so the platform can be exercised end to end.)
 */
@Service
public class DepositService {

    private final AccountService accountService;
    private final AccountLocks accountLocks;
    private final LedgerService ledgerService;

    public DepositService(AccountService accountService, AccountLocks accountLocks, LedgerService ledgerService) {
        this.accountService = accountService;
        this.accountLocks = accountLocks;
        this.ledgerService = ledgerService;
    }

    public record DepositResult(LedgerTransaction transaction, Account account) {
    }

    @Transactional
    public DepositResult deposit(UUID accountId, Money amount) {
        if (!amount.isPositive()) {
            throw new BusinessRuleViolationException("invalid_amount", "Deposit amount must be positive");
        }
        SupportedCurrencies.require(amount.currency());
        UUID fundingId = accountService.systemAccountId(AccountType.EXTERNAL_FUNDING, amount.currency());
        Map<UUID, Account> locked = accountLocks.lockForBalanceChange(List.of(accountId, fundingId));
        Account wallet = locked.get(accountId);

        if (wallet.getType() != AccountType.CUSTOMER_WALLET) {
            throw new BusinessRuleViolationException("not_a_wallet", "Deposits are only allowed into customer wallets");
        }
        wallet.requireActive();
        if (!wallet.getCurrency().equals(amount.currency())) {
            throw new CurrencyMismatchException(wallet.getCurrency(), amount.currency());
        }

        LedgerTransaction transaction = ledgerService.post(
                "DEP-" + UUID.randomUUID(),
                LedgerTransactionType.DEPOSIT,
                List.of(Posting.debit(fundingId, amount), Posting.credit(wallet.getId(), amount)),
                locked);
        return new DepositResult(transaction, wallet);
    }
}
