package com.kgiannopoulou.ledgerforge.transfer.application;

import com.kgiannopoulou.ledgerforge.shared.event.TransferCompletedEvent;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEventPublisher;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditedEntity;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditAction;
import com.kgiannopoulou.ledgerforge.audit.application.AuditTrail;
import com.kgiannopoulou.ledgerforge.account.application.AccountLocks;
import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.account.domain.AccountType;
import com.kgiannopoulou.ledgerforge.account.domain.InsufficientFundsException;
import com.kgiannopoulou.ledgerforge.ledger.application.LedgerService;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransaction;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransactionType;
import com.kgiannopoulou.ledgerforge.ledger.domain.Posting;
import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.error.NotFoundException;
import com.kgiannopoulou.ledgerforge.shared.money.CurrencyMismatchException;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import com.kgiannopoulou.ledgerforge.shared.web.LogContext;
import com.kgiannopoulou.ledgerforge.transfer.domain.Transfer;
import com.kgiannopoulou.ledgerforge.transfer.domain.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transfers;
    private final AccountLocks accountLocks;
    private final LedgerService ledgerService;
    private final AuditTrail audit;
    private final IntegrationEventPublisher events;
    private final Clock clock;

    public TransferService(TransferRepository transfers, AccountLocks accountLocks, LedgerService ledgerService,
                           AuditTrail audit, IntegrationEventPublisher events, Clock clock) {
        this.transfers = transfers;
        this.accountLocks = accountLocks;
        this.ledgerService = ledgerService;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record TransferCommand(UUID sourceAccountId, UUID destinationAccountId, Money amount) {
    }

    /**
     * Validates and books a transfer atomically: transfer row, ledger transaction, debit, credit
     * and both balance updates commit together or not at all.
     *
     * <p>All checks that depend on balances or statuses run <em>after</em> the accounts are locked;
     * checking first and locking later would be a time-of-check/time-of-use race.
     */
    @Transactional
    public Transfer transfer(TransferCommand command) {
        Money amount = command.amount();
        if (!amount.isPositive()) {
            throw new BusinessRuleViolationException("INVALID_AMOUNT", "Transfer amount must be positive");
        }
        if (command.sourceAccountId().equals(command.destinationAccountId())) {
            throw new BusinessRuleViolationException("SAME_ACCOUNT", "Source and destination accounts must differ");
        }

        Map<UUID, Account> locked = accountLocks.lockForBalanceChange(
                List.of(command.sourceAccountId(), command.destinationAccountId()));
        Account source = locked.get(command.sourceAccountId());
        Account destination = locked.get(command.destinationAccountId());

        for (Account account : List.of(source, destination)) {
            if (account.getType() != AccountType.CUSTOMER_WALLET) {
                throw new BusinessRuleViolationException("NOT_A_WALLET",
                        "Transfers are only allowed between customer wallets");
            }
            account.requireActive();
        }
        if (!source.getCurrency().equals(destination.getCurrency())) {
            throw new CurrencyMismatchException(source.getCurrency(), destination.getCurrency());
        }
        if (!source.getCurrency().equals(amount.currency())) {
            throw new CurrencyMismatchException(source.getCurrency(), amount.currency());
        }
        if (!source.canCover(amount)) {
            throw new InsufficientFundsException(source.getId());
        }

        Transfer transfer = transfers.save(
                Transfer.initiate(source.getId(), destination.getId(), amount, clock.instant()));
        LogContext.put(LogContext.TRANSFER_ID, transfer.getId());
        LedgerTransaction booked = ledgerService.post(
                transfer.ledgerReference(),
                LedgerTransactionType.TRANSFER,
                List.of(Posting.debit(source.getId(), amount), Posting.credit(destination.getId(), amount)),
                locked);
        transfer.complete(booked.getId(), clock.instant());

        // Same transaction as the money movement: all of it commits, or none of it.
        audit.record(AuditAction.TRANSFER_CREATED, AuditedEntity.TRANSFER, transfer.getId(), Map.of(
                "sourceAccountId", source.getId().toString(),
                "destinationAccountId", destination.getId().toString(),
                "amount", amount.amount().toPlainString(),
                "currency", amount.currency().getCurrencyCode(),
                "ledgerTransactionId", booked.getId().toString()));
        events.publish(new TransferCompletedEvent(UUID.randomUUID(), transfer.getId(), source.getId(),
                destination.getId(), amount.amount(), amount.currency(), clock.instant()));
        log.info("Transfer completed");
        return transfer;
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID id) {
        return transfers.findById(id).orElseThrow(() -> new NotFoundException("Transfer", id));
    }
}
