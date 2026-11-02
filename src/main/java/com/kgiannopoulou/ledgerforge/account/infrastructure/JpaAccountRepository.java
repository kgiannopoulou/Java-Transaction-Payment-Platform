package com.kgiannopoulou.ledgerforge.account.infrastructure;

import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.account.domain.AccountRepository;
import com.kgiannopoulou.ledgerforge.account.domain.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

interface JpaAccountRepository extends JpaRepository<Account, UUID>, AccountRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);

    @Override
    @Query("select a.id from Account a where a.type = :type and a.currency = :currency")
    Optional<UUID> findSystemAccountId(AccountType type, Currency currency);

    @Override
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE accounts SET balance = :balance WHERE id = :id", nativeQuery = true)
    int overwriteBalanceUnsafe(UUID id, BigDecimal balance);
}
