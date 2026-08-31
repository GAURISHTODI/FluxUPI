package com.fluxupi.ledger;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.LedgerImbalanceException;
import com.fluxupi.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes double-entry journal entries and is the only class allowed to do so.
 *
 * <p><b>Why {@code Propagation.MANDATORY}.</b> Posting to the ledger must happen
 * inside the caller's transaction, alongside the {@code availableLimit} update.
 * If this method opened its own transaction, a spend could commit ledger entries
 * and then fail to commit the limit change — the books and the balance would
 * disagree, permanently. MANDATORY makes "someone called this without a
 * transaction" a startup-obvious failure rather than a rare production
 * inconsistency.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Validates that {@code postings} balance and appends them to the book.
     *
     * @throws LedgerImbalanceException if there are fewer than two lines, or if
     *                                  debits do not exactly equal credits
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> post(Transaction transaction, List<LedgerPosting> postings) {
        requireBalanced(transaction.getId(), postings);

        UUID creditLineId = transaction.getCreditLine().getId();
        List<LedgerEntry> entries = new ArrayList<>(postings.size());
        for (int seq = 0; seq < postings.size(); seq++) {
            entries.add(new LedgerEntry(transaction.getId(), creditLineId, postings.get(seq), seq));
        }

        List<LedgerEntry> saved = ledgerEntryRepository.saveAll(entries);
        log.debug("Posted {} balanced ledger entries for transaction {}", saved.size(), transaction.getId());
        return saved;
    }

    /**
     * The invariant, in one place. Called before every write, and re-used by
     * tests and the reconciliation report so there is exactly one definition of
     * "balanced" in the codebase.
     */
    public static void requireBalanced(Object transactionRef, List<LedgerPosting> postings) {
        if (postings == null || postings.size() < 2) {
            throw new LedgerImbalanceException(
                    "A journal entry for %s needs at least two lines, got %d"
                            .formatted(transactionRef, postings == null ? 0 : postings.size()));
        }

        BigDecimal debits = Money.ZERO;
        BigDecimal credits = Money.ZERO;
        for (LedgerPosting posting : postings) {
            if (posting.isDebit()) {
                debits = debits.add(posting.amount());
            } else {
                credits = credits.add(posting.amount());
            }
        }

        debits = Money.normalize(debits);
        credits = Money.normalize(credits);
        if (!Money.isEqual(debits, credits)) {
            throw new LedgerImbalanceException(transactionRef, debits, credits);
        }
        if (Money.isZero(debits)) {
            throw new LedgerImbalanceException(
                    "A journal entry for %s must move a non-zero amount".formatted(transactionRef));
        }
    }

    // ------------------------------------------------------------ reconciliation

    /**
     * Recomputes the whole book. Cheap enough to run in a test after 1,000+
     * transactions, and exposed over HTTP so the invariant can be demonstrated
     * live rather than only asserted in CI.
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        BigDecimal debits = Money.normalize(ledgerEntryRepository.totalDebits());
        BigDecimal credits = Money.normalize(ledgerEntryRepository.totalCredits());
        List<UUID> imbalanced = ledgerEntryRepository.findImbalancedTransactions().stream()
                .map(LedgerEntryRepository.TransactionImbalance::getTransactionId)
                .toList();
        List<UUID> malformed = ledgerEntryRepository.findTransactionsWithTooFewEntries();
        long entryCount = ledgerEntryRepository.count();

        return new ReconciliationReport(entryCount, debits, credits, imbalanced, malformed,
                ledgerEntryRepository.findAccountBalances().stream()
                        .map(b -> new AccountPosition(b.getAccount(), Money.normalize(b.getNetDebit())))
                        .toList());
    }

    public record AccountPosition(LedgerAccount account, BigDecimal netDebit) {
    }

    /**
     * The answer to "do the books balance?" — globally, per transaction, and
     * structurally (no single-sided entries).
     */
    public record ReconciliationReport(long entryCount,
                                       BigDecimal totalDebits,
                                       BigDecimal totalCredits,
                                       List<UUID> imbalancedTransactionIds,
                                       List<UUID> malformedTransactionIds,
                                       List<AccountPosition> accountPositions) {

        public boolean isBalanced() {
            return Money.isEqual(totalDebits, totalCredits)
                    && imbalancedTransactionIds.isEmpty()
                    && malformedTransactionIds.isEmpty();
        }

        public BigDecimal difference() {
            return Money.normalize(totalDebits.subtract(totalCredits));
        }
    }
}
