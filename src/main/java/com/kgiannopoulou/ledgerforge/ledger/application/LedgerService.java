package com.kgiannopoulou.ledgerforge.ledger.application;

import com.kgiannopoulou.ledgerforge.account.application.AccountLocks;
import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.ledger.domain.EntryDirection;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerRepository;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransaction;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransactionType;
import com.kgiannopoulou.ledgerforge.ledger.domain.Posting;
import com.kgiannopoulou.ledgerforge.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The single entry point for moving money. Every balance change in LedgerForge goes through
 * {@link #post}, which:
 * <ol>
 *   <li>builds a {@link LedgerTransaction} (refusing unbalanced postings),</li>
 *   <li>applies each posting to the corresponding, already locked, account balance,</li>
 *   <li>persists entries and balances in the caller's transaction.</li>
 * </ol>
 * It never opens its own transaction ({@code MANDATORY}): the caller decides the transaction
 * boundary so that business records (transfer, payment) and ledger entries commit or roll back
 * together.
 */
@Service
public class LedgerService {

    private final LedgerRepository ledger;
    private final AccountLocks accountLocks;
    private final Clock clock;

    public LedgerService(LedgerRepository ledger, AccountLocks accountLocks, Clock clock) {
        this.ledger = ledger;
        this.accountLocks = accountLocks;
        this.clock = clock;
    }

    /**
     * @param lockedAccounts accounts obtained from {@link AccountLocks#lockForBalanceChange} in the
     *                       current transaction; must contain every account referenced by a posting
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(String reference, LedgerTransactionType type, List<Posting> postings,
                                  Map<UUID, Account> lockedAccounts) {
        UUID transactionId = UUID.randomUUID();
        LedgerTransaction transaction = LedgerTransaction.record(transactionId, reference, type, postings, clock.instant());

        Set<Account> touched = new LinkedHashSet<>();
        for (Posting posting : postings) {
            Account account = lockedAccounts.get(posting.accountId());
            if (account == null) {
                throw new IllegalStateException("Account " + posting.accountId() + " was not locked before posting");
            }
            if (posting.direction() == EntryDirection.DEBIT) {
                account.debit(posting.amount());
            } else {
                account.credit(posting.amount());
            }
            touched.add(account);
        }
        accountLocks.writeBalances(new ArrayList<>(touched));
        return ledger.save(transaction);
    }

    @Transactional(readOnly = true)
    public LedgerTransaction get(UUID id) {
        LedgerTransaction transaction = ledger.findById(id)
                .orElseThrow(() -> new NotFoundException("Ledger transaction", id));
        transaction.getEntries().size(); // initialise lazily loaded entries inside the transaction
        return transaction;
    }
}
