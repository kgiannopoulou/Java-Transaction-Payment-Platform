package com.kgiannopoulou.ledgerforge.payment.infrastructure;

import com.kgiannopoulou.ledgerforge.payment.domain.Payment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface JpaPaymentRepository extends JpaRepository<Payment, UUID>, PaymentRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(UUID id);
}
