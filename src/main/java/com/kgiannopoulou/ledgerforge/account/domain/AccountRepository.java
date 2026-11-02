package com.kgiannopoulou.ledgerforge.account.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(UUID id);

    /** {@code SELECT ... FOR UPDATE}: blocks until no other transaction holds the row lock. */
    Optional<Account> findByIdForUpdate(UUID id);

    /**
     * Id only, on purpose: loading the entity here would put an unlocked, possibly stale copy
     * into the persistence context, and a later {@code SELECT ... FOR UPDATE} returns that same
     * cached instance without refreshing its balance.
     */
    Optional<UUID> findSystemAccountId(AccountType type, Currency currency);

    List<Account> findByCustomerIdOrderByCreatedAt(UUID customerId);

    boolean existsByCustomerIdAndCurrency(UUID customerId, Currency currency);

    /**
     * Blindly overwrites the balance without a version check. Used only by
     * {@code LockingMode.NAIVE} to reproduce the lost-update anomaly in tests.
     */
    int overwriteBalanceUnsafe(UUID id, BigDecimal balance);
}
