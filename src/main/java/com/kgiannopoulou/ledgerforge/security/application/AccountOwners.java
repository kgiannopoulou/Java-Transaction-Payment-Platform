package com.kgiannopoulou.ledgerforge.security.application;

import com.kgiannopoulou.ledgerforge.account.domain.AccountRepository;
import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStore;
import com.kgiannopoulou.ledgerforge.shared.ephemeral.EphemeralStoreUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * "Who owns this account?", the check behind nearly every customer request.
 *
 * <p>A good thing to cache because the answer cannot go stale: {@code customer_id} never changes
 * after a wallet is created. Balances, statuses and anything else that can change are deliberately
 * not cached. Unknown ids are not cached either, so an id cannot be "reserved" before it exists.
 * If Redis is unavailable, the answer simply comes from PostgreSQL.
 */
@Component
public class AccountOwners {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final AccountRepository accounts;
    private final EphemeralStore store;
    private final MeterRegistry meters;

    public AccountOwners(AccountRepository accounts, EphemeralStore store, MeterRegistry meters) {
        this.accounts = accounts;
        this.store = store;
        this.meters = meters;
    }

    public Optional<UUID> ownerOf(UUID accountId) {
        String key = "cache:account-owner:" + accountId;
        try {
            Optional<String> cached = store.get(key);
            if (cached.isPresent()) {
                record("hit");
                return cached.map(UUID::fromString);
            }
        } catch (EphemeralStoreUnavailableException e) {
            record("unavailable");
            return accounts.findCustomerIdById(accountId);
        }
        record("miss");
        Optional<UUID> owner = accounts.findCustomerIdById(accountId);
        owner.ifPresent(id -> {
            try {
                store.put(key, id.toString(), TTL);
            } catch (EphemeralStoreUnavailableException ignored) {
                // the next lookup goes to PostgreSQL again
            }
        });
        return owner;
    }

    private void record(String result) {
        meters.counter("ledgerforge.cache.requests", "cache", "account-owner", "result", result).increment();
    }
}
