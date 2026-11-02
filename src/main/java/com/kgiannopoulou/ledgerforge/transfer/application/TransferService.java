package com.kgiannopoulou.ledgerforge.transfer.application;

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
import com.kgiannopoulou.ledgerforge.transfer.domain.Transfer;
import com.kgiannopoulou.ledgerforge.transfer.domain.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransferService {

    private final TransferRepository transfers;
    private final AccountLocks accountLocks;
    private final LedgerService ledgerService;
    private final Clock clock;

    public TransferService(TransferRepository transfers, AccountLocks accountLocks, LedgerService ledgerService,
                           Clock clock) {
        this.transfers = transfers;
        this.accountLocks = accountLocks;
        this.ledgerService = ledgerService;
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
            throw new BusinessRuleViolationException("invalid_amount", "Transfer amount must be positive");
        }
        if (command.sourceAccountId().equals(command.destinationAccountId())) {
            throw new BusinessRuleViolationException("same_account", "Source and destination accounts must differ");
        }

        Map<UUID, Account> locked = accountLocks.lockForBalanceChange(
                List.of(command.sourceAccountId(), command.destinationAccountId()));
        Account source = locked.get(command.sourceAccountId());
        Account destination = locked.get(command.destinationAccountId());

        for (Account account : List.of(source, destination)) {
            if (account.getType() != AccountType.CUSTOMER_WALLET) {
                throw new BusinessRuleViolationException("not_a_wallet",
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
        LedgerTransaction booked = ledgerService.post(
                transfer.ledgerReference(),
                LedgerTransactionType.TRANSFER,
                List.of(Posting.debit(source.getId(), amount), Posting.credit(destination.getId(), amount)),
                locked);
        transfer.complete(booked.getId(), clock.instant());
        return transfer;
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID id) {
        return transfers.findById(id).orElseThrow(() -> new NotFoundException("Transfer", id));
    }
}
