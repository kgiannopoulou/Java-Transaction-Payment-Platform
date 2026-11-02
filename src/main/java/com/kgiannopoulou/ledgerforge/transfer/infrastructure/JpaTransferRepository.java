package com.kgiannopoulou.ledgerforge.transfer.infrastructure;

import com.kgiannopoulou.ledgerforge.transfer.domain.Transfer;
import com.kgiannopoulou.ledgerforge.transfer.domain.TransferRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface JpaTransferRepository extends JpaRepository<Transfer, UUID>, TransferRepository {
}
