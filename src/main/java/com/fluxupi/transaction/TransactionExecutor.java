package com.fluxupi.transaction;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.ResourceNotFoundException;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineRepository;
import com.fluxupi.ledger.Journal;
import com.fluxupi.ledger.LedgerPosting;
import com.fluxupi.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The atomic units of work behind {@link TransactionService}.
 *
 * <p>Each public method here is exactly one database transaction. The split
 * exists because idempotency handling has to survive a rollback: if a spend
 * loses the race on the {@code idempotency_key} unique constraint, the losing
 * transaction is already doomed, so the "go read the winner's row" step must
 * happen <em>outside</em> it. {@code TransactionService} owns that retry;
 * this class owns the work itself.
 *
 * <p>The ordering inside {@link #executeSpend} is the whole concurrency story:
 * <ol>
 *   <li>take a {@code SELECT ... FOR UPDATE} row lock on the credit line;</li>
 *   <li>check and decrement {@code availableLimit};</li>
 *   <li>write the transaction row (flushing so a duplicate key surfaces here);</li>
 *   <li>write the balanced ledger entries;</li>
 *   <li>commit all of it together.</li>
 * </ol>
 * A second concurrent spend blocks at step 1 and therefore reads the balance
 * the first one left behind. There is no window in which the limit has moved
 * but the ledger has not, or vice versa.
 */
@Component
public class TransactionExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransactionExecutor.class);

    private final CreditLineRepository creditLineRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final jakarta.persistence.EntityManager entityManager;

    public TransactionExecutor(CreditLineRepository creditLineRepository,
                               TransactionRepository transactionRepository,
                               LedgerService ledgerService,
                               jakarta.persistence.EntityManager entityManager) {
        this.creditLineRepository = creditLineRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
        this.entityManager = entityManager;
    }

    @Transactional
    public Transaction executeSpend(SpendCommand command, String fingerprint) {
        CreditLine creditLine = lockCreditLine(command.creditLineId());

        Transaction transaction = Transaction.spend(creditLine, command.amount(), command.idempotencyKey(),
                fingerprint, command.payeeVpa(), command.description());

        // Throws InsufficientCreditLimitException or IllegalStateTransitionException
        // before anything is written, so a refused spend leaves no partial trace.
        creditLine.authorizeSpend(transaction.getAmount());

        persistAndPost(transaction, Journal.forSpend(transaction.getAmount(), transaction.getPayeeVpa()));
        creditLineRepository.save(creditLine);

        log.debug("Spend {} of {} committed; credit line {} now has {} available",
                transaction.getId(), transaction.getAmount(), creditLine.getId(), creditLine.getAvailableLimit());
        return transaction;
    }

    @Transactional
    public Transaction executeReversal(UUID originalTransactionId, String reason,
                                       String idempotencyKey, String fingerprint) {
        Transaction original = transactionRepository.findById(originalTransactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", originalTransactionId));

        // Take the credit-line row lock first, then re-read the original under
        // that lock. Two threads racing to reverse the same spend serialise
        // here; the second one reads the now-REVERSED status and its
        // markReversed() below fails the state-machine guard, rather than acting
        // on a stale SUCCESS snapshot and crediting the limit twice.
        CreditLine creditLine = lockCreditLine(original.getCreditLine().getId());
        entityManager.refresh(original);

        Transaction reversal = Transaction.reversal(original, idempotencyKey, fingerprint, reason);

        // The state machine is what prevents double reversal: SUCCESS is the
        // only state with an edge to REVERSED, so a second attempt throws
        // IllegalStateTransitionException.
        original.markReversed();
        creditLine.restoreLimit(original.getAmount());

        persistAndPost(reversal, Journal.forReversal(original.getAmount(), original.getPayeeVpa(), reason));
        transactionRepository.save(original);
        creditLineRepository.save(creditLine);

        log.debug("Reversal {} unwound spend {}; credit line {} restored to {}",
                reversal.getId(), original.getId(), creditLine.getId(), creditLine.getAvailableLimit());
        return reversal;
    }

    /**
     * Settles a repayment: cash in, principal and interest recognised separately.
     * The headroom restored to the line is the principal only — paying interest
     * does not buy you more to spend.
     */
    @Transactional
    public Transaction executeRepayment(UUID creditLineId, BigDecimal principal, BigDecimal interest,
                                        BigDecimal fees, String idempotencyKey, String fingerprint,
                                        String description) {
        CreditLine creditLine = lockCreditLine(creditLineId);

        BigDecimal total = Money.normalize(principal.add(interest).add(fees));
        Transaction transaction = Transaction.repayment(creditLine, total, idempotencyKey,
                fingerprint, description);

        persistAndPost(transaction, Journal.forRepayment(principal, interest, fees));
        if (Money.isPositive(principal)) {
            creditLine.restoreLimit(principal);
        }
        creditLineRepository.save(creditLine);

        log.debug("Repayment {} of {} ({} principal / {} interest) settled on credit line {}",
                transaction.getId(), total, principal, interest, creditLineId);
        return transaction;
    }

    /**
     * Records a refused attempt as a FAILED transaction in its own transaction,
     * so the audit trail keeps the rejection even though the work rolled back.
     *
     * <p>{@code REQUIRES_NEW} is essential: the caller's transaction is already
     * marked rollback-only by the exception that got us here, and anything
     * written on it would vanish.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(SpendCommand command, String fingerprint, String reason) {
        try {
            CreditLine creditLine = creditLineRepository.findById(command.creditLineId()).orElse(null);
            if (creditLine == null) {
                return;
            }
            Transaction failed = Transaction.spend(creditLine, command.amount(), command.idempotencyKey(),
                    fingerprint, command.payeeVpa(), command.description());
            failed.markFailed(reason);
            transactionRepository.saveAndFlush(failed);
        } catch (DataIntegrityViolationException | IllegalArgumentException e) {
            // Best-effort audit only. If the key was taken in the meantime, or
            // the amount was never valid, there is nothing useful to record and
            // the caller's original exception is the one that matters.
            log.debug("Could not record failed attempt for key {}: {}", command.idempotencyKey(), e.toString());
        }
    }

    private CreditLine lockCreditLine(UUID creditLineId) {
        return creditLineRepository.findByIdForUpdate(creditLineId)
                .orElseThrow(() -> new ResourceNotFoundException("CreditLine", creditLineId));
    }

    /**
     * Writes the transaction row, then its ledger entries, in that order.
     *
     * <p>The {@code saveAndFlush} is deliberate. Without it Hibernate would
     * defer the INSERT to commit time, and a duplicate {@code idempotency_key}
     * would surface as a constraint violation from somewhere far away instead
     * of from the statement that caused it.
     */
    private void persistAndPost(Transaction transaction, List<LedgerPosting> postings) {
        transactionRepository.saveAndFlush(transaction);
        ledgerService.post(transaction, postings);
        transaction.markSuccess();
        transactionRepository.save(transaction);
    }
}
