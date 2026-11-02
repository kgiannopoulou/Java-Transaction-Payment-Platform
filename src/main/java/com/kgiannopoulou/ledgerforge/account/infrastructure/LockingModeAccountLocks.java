package com.kgiannopoulou.ledgerforge.account.infrastructure;

import com.kgiannopoulou.ledgerforge.account.application.AccountLocks;
import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.account.domain.AccountRepository;
import com.kgiannopoulou.ledgerforge.shared.error.NotFoundException;
import com.kgiannopoulou.ledgerforge.shared.transaction.ConcurrencyProperties;
import com.kgiannopoulou.ledgerforge.shared.transaction.LockingMode;
import com.kgiannopoulou.ledgerforge.shared.transaction.SimulatedLatency;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link AccountLocks} implementation that switches on {@link LockingMode}.
 *
 * <ul>
 *   <li><b>PESSIMISTIC</b> - {@code SELECT ... FOR UPDATE}, one row at a time in ascending UUID order.
 *       Two transfers A&rarr;B and B&rarr;A both lock min(A,B) first, so they queue instead of
 *       deadlocking.</li>
 *   <li><b>OPTIMISTIC</b> - plain reads; Hibernate's {@code UPDATE ... WHERE version = ?} detects
 *       concurrent writers at flush time and the caller's transaction is retried.</li>
 *   <li><b>NAIVE</b> - plain reads on <em>detached</em> entities, then a blind
 *       {@code UPDATE accounts SET balance = ?}. This is the classic lost-update bug and exists
 *       solely so the test suite can demonstrate it.</li>
 * </ul>
 */
@Component
class LockingModeAccountLocks implements AccountLocks {

    private final AccountRepository accounts;
    private final EntityManager entityManager;
    private final LockingMode mode;
    private final SimulatedLatency simulatedLatency;

    LockingModeAccountLocks(AccountRepository accounts, EntityManager entityManager,
                            ConcurrencyProperties properties, SimulatedLatency simulatedLatency) {
        this.accounts = accounts;
        this.entityManager = entityManager;
        this.mode = properties.lockingMode();
        this.simulatedLatency = simulatedLatency;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SortedMap<UUID, Account> lockForBalanceChange(Collection<UUID> accountIds) {
        SortedMap<UUID, Account> loaded = new TreeMap<>();
        // TreeSet = deterministic lock acquisition order (UUID natural ordering).
        for (UUID id : new TreeSet<>(accountIds)) {
            Account account = switch (mode) {
                case PESSIMISTIC -> accounts.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Account", id));
                case OPTIMISTIC -> accounts.findById(id).orElseThrow(() -> new NotFoundException("Account", id));
                case NAIVE -> {
                    Account snapshot = accounts.findById(id).orElseThrow(() -> new NotFoundException("Account", id));
                    entityManager.detach(snapshot); // changes will be written blindly, see writeBalances
                    yield snapshot;
                }
            };
            loaded.put(id, account);
        }
        simulatedLatency.pause();
        return loaded;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeBalances(Collection<Account> changed) {
        if (mode == LockingMode.NAIVE) {
            for (Account account : changed) {
                accounts.overwriteBalanceUnsafe(account.getId(), account.getBalance().amount());
            }
        }
        // PESSIMISTIC / OPTIMISTIC: entities are managed; dirty checking flushes them at commit.
    }
}
