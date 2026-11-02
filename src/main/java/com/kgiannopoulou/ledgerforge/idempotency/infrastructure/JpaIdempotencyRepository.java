package com.kgiannopoulou.ledgerforge.idempotency.infrastructure;

import com.kgiannopoulou.ledgerforge.idempotency.domain.IdempotencyKey;
import com.kgiannopoulou.ledgerforge.idempotency.domain.IdempotencyRecord;
import com.kgiannopoulou.ledgerforge.idempotency.domain.IdempotencyRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

interface JpaIdempotencyRepository extends JpaRepository<IdempotencyRecord, IdempotencyKey>, IdempotencyRepository {

    @Override
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (scope, idempotency_key, request_hash, status, created_at, expires_at)
            VALUES (:scope, :key, :requestHash, 'IN_PROGRESS', :createdAt, :expiresAt)
            ON CONFLICT (scope, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(String scope, String key, String requestHash, Instant createdAt, Instant expiresAt);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IdempotencyRecord r where r.id = :id")
    Optional<IdempotencyRecord> findForUpdate(IdempotencyKey id);

    @Override
    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt < :now")
    int deleteExpired(Instant now);
}
