package com.kgiannopoulou.ledgerforge.account.application;

import com.kgiannopoulou.ledgerforge.shared.event.AccountFrozenEvent;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEventPublisher;
import java.util.Map;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditedEntity;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditAction;
import com.kgiannopoulou.ledgerforge.audit.application.AuditTrail;
import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.account.domain.AccountRepository;
import com.kgiannopoulou.ledgerforge.account.domain.AccountType;
import com.kgiannopoulou.ledgerforge.account.domain.SupportedCurrencies;
import com.kgiannopoulou.ledgerforge.customer.domain.Customer;
import com.kgiannopoulou.ledgerforge.customer.domain.CustomerRepository;
import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;
import com.kgiannopoulou.ledgerforge.shared.error.NotFoundException;
import com.kgiannopoulou.ledgerforge.shared.transaction.RetryingTransactionExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final AccountLocks accountLocks;
    private final RetryingTransactionExecutor transactions;
    private final AuditTrail audit;
    private final IntegrationEventPublisher events;
    private final Clock clock;

    public AccountService(AccountRepository accounts, CustomerRepository customers, AccountLocks accountLocks,
                          RetryingTransactionExecutor transactions, AuditTrail audit,
                          IntegrationEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.customers = customers;
        this.accountLocks = accountLocks;
        this.transactions = transactions;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Account openWallet(UUID customerId, Currency currency) {
        SupportedCurrencies.require(currency);
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer", customerId));
        if (!customer.isActive()) {
            throw new BusinessRuleViolationException("CUSTOMER_NOT_ACTIVE", "Customer " + customerId + " is not active");
        }
        if (accounts.existsByCustomerIdAndCurrency(customerId, currency)) {
            throw new ConflictException("ACCOUNT_ALREADY_EXISTS",
                    "Customer " + customerId + " already has a " + currency + " wallet");
        }
        Account wallet = accounts.save(Account.openWallet(customerId, currency, clock.instant()));
        audit.record(AuditAction.ACCOUNT_CREATED, AuditedEntity.ACCOUNT, wallet.getId(),
                Map.of("customerId", customerId.toString(), "currency", currency.getCurrencyCode()));
        return wallet;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account", id));
    }

    @Transactional(readOnly = true)
    public List<Account> listForCustomer(UUID customerId) {
        customers.findById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        return accounts.findByCustomerIdOrderByCreatedAt(customerId);
    }

    @Transactional(readOnly = true)
    public UUID systemAccountId(AccountType type, Currency currency) {
        return accounts.findSystemAccountId(type, currency)
                .orElseThrow(() -> new NotFoundException(type + " account", currency));
    }

    public Account freeze(UUID id) {
        return changeStatus(id, Account::freeze, AuditAction.ACCOUNT_FROZEN, account ->
                events.publish(new AccountFrozenEvent(UUID.randomUUID(), account.getId(), account.getCustomerId(),
                        clock.instant())));
    }

    public Account unfreeze(UUID id) {
        return changeStatus(id, Account::unfreeze, AuditAction.ACCOUNT_UNFROZEN, account -> { });
    }

    public Account close(UUID id) {
        return changeStatus(id, Account::close, AuditAction.ACCOUNT_CLOSED, account -> { });
    }

    /** Status changes take the same row lock as money movements, so "close" cannot race a deposit. */
    private Account changeStatus(UUID id, Consumer<Account> change, AuditAction action,
                                 Consumer<Account> afterChange) {
        return transactions.execute(() -> {
            Account account = accountLocks.lockForBalanceChange(List.of(id)).get(id);
            change.accept(account);
            Account saved = accounts.save(account);
            audit.record(action, AuditedEntity.ACCOUNT, saved.getId(), Map.of("status", saved.getStatus().name()));
            afterChange.accept(saved);
            return saved;
        });
    }
}
