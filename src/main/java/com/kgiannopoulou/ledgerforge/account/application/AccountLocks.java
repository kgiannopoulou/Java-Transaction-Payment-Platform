package com.kgiannopoulou.ledgerforge.account.application;

import com.kgiannopoulou.ledgerforge.account.domain.Account;

import java.util.Collection;
import java.util.SortedMap;
import java.util.UUID;

/**
 * Loads accounts whose balances are about to change, protected according to the configured
 * {@link com.kgiannopoulou.ledgerforge.shared.transaction.LockingMode}.
 *
 * <p>Must be called inside a transaction. Callers should load <em>all</em> accounts they will
 * touch in a single call so the implementation can acquire locks in a deterministic order.
 */
public interface AccountLocks {

    /**
     * @return the accounts keyed (and ordered) by id
     * @throws com.kgiannopoulou.ledgerforge.shared.error.NotFoundException if any id is unknown
     */
    SortedMap<UUID, Account> lockForBalanceChange(Collection<UUID> accountIds);

    /** Makes in-memory balance changes durable. A no-op for JPA-managed (locking) modes. */
    void writeBalances(Collection<Account> accounts);
}
