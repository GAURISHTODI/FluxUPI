package com.fluxupi.transaction;

import com.fluxupi.common.Fingerprint;
import com.fluxupi.common.Money;
import com.fluxupi.common.exception.DuplicateIdempotencyKeyException;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.common.exception.InsufficientCreditLimitException;
import com.fluxupi.common.exception.ResourceNotFoundException;
import com.fluxupi.notification.DomainEventPublisher;
import com.fluxupi.notification.TransactionSettledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The public entry point for spends, reversals and repayments.
 *
 * <p>This class owns <b>idempotency</b> and nothing else; the actual database
 * work lives in {@link TransactionExecutor}. There are three cases to get right,
 * and the third is the one naive implementations miss:
 *
 * <ol>
 *   <li><b>Same key, same payload</b> — a genuine retry. Return the stored
 *       result, marked {@code replayed}. No new money moves.</li>
 *   <li><b>Same key, different payload</b> — a client bug. Reject with
 *       {@link DuplicateIdempotencyKeyException} rather than silently returning
 *       someone else's transaction.</li>
 *   <li><b>Two identical requests racing</b> — both miss the lookup and both
 *       try to insert. The database's {@code UNIQUE} constraint picks a winner;
 *       the loser catches the violation, re-reads, and replays. This is why the
 *       constraint has to be in the schema: an application-level check alone
 *       has a window between "not found" and "insert" that both requests fit
 *       through.</li>
 * </ol>
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionExecutor executor;
    private final TransactionRepository transactionRepository;
    private final DomainEventPublisher eventPublisher;

    public TransactionService(TransactionExecutor executor,
                              TransactionRepository transactionRepository,
                              DomainEventPublisher eventPublisher) {
        this.executor = executor;
        this.transactionRepository = transactionRepository;
        this.eventPublisher = eventPublisher;
    }

    public TransactionResult spend(SpendCommand command) {
        String fingerprint = Fingerprint.of("SPEND", command.creditLineId(),
                Money.normalize(command.amount()), command.payeeVpa());

        Optional<Transaction> alreadyProcessed = transactionRepository
                .findByIdempotencyKey(command.idempotencyKey());
        if (alreadyProcessed.isPresent()) {
            return replay(alreadyProcessed.get(), fingerprint, command.idempotencyKey());
        }

        try {
            Transaction transaction = executor.executeSpend(command, fingerprint);
            eventPublisher.publish(TransactionSettledEvent.from(transaction));
            return TransactionResult.processed(transaction);
        } catch (DataIntegrityViolationException race) {
            // Case 3: we lost the unique-constraint race. The winner's row is
            // authoritative, and our own transaction wrote nothing.
            log.debug("Idempotency race on key {}; deferring to the winning insert", command.idempotencyKey());
            Transaction winner = transactionRepository.findByIdempotencyKey(command.idempotencyKey())
                    .orElseThrow(() -> race);
            return replay(winner, fingerprint, command.idempotencyKey());
        } catch (InsufficientCreditLimitException | IllegalStateTransitionException refusal) {
            executor.recordFailure(command, fingerprint, refusal.getMessage());
            throw refusal;
        }
    }

    public TransactionResult reverse(UUID originalTransactionId, String reason, String idempotencyKey) {
        String fingerprint = Fingerprint.of("REVERSAL", originalTransactionId);

        Optional<Transaction> alreadyProcessed = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (alreadyProcessed.isPresent()) {
            return replay(alreadyProcessed.get(), fingerprint, idempotencyKey);
        }

        try {
            Transaction reversal = executor.executeReversal(originalTransactionId, reason,
                    idempotencyKey, fingerprint);
            eventPublisher.publish(TransactionSettledEvent.from(reversal));
            return TransactionResult.processed(reversal);
        } catch (DataIntegrityViolationException race) {
            Transaction winner = transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> race);
            return replay(winner, fingerprint, idempotencyKey);
        }
    }

    /**
     * Settles a repayment with an explicit principal/interest/fee split, as
     * computed by the repayment schedule.
     */
    public TransactionResult repay(UUID creditLineId, BigDecimal principal, BigDecimal interest,
                                   BigDecimal fees, String description, String idempotencyKey) {
        String fingerprint = Fingerprint.of("REPAYMENT", creditLineId,
                Money.normalize(principal), Money.normalize(interest), Money.normalize(fees));

        Optional<Transaction> alreadyProcessed = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (alreadyProcessed.isPresent()) {
            return replay(alreadyProcessed.get(), fingerprint, idempotencyKey);
        }

        try {
            Transaction repayment = executor.executeRepayment(creditLineId, principal, interest, fees,
                    idempotencyKey, fingerprint, description);
            eventPublisher.publish(TransactionSettledEvent.from(repayment));
            return TransactionResult.processed(repayment);
        } catch (DataIntegrityViolationException race) {
            Transaction winner = transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> race);
            return replay(winner, fingerprint, idempotencyKey);
        }
    }

    @Transactional(readOnly = true)
    public Transaction findById(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    @Transactional(readOnly = true)
    public List<Transaction> findAllForCreditLine(UUID creditLineId) {
        return transactionRepository.findAllByCreditLineIdOrderByCreatedAtAsc(creditLineId);
    }

    /**
     * Decides between case 1 and case 2 above: the fingerprint has to match, or
     * the caller reused a key for different money.
     */
    private TransactionResult replay(Transaction existing, String fingerprint, String idempotencyKey) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new DuplicateIdempotencyKeyException(idempotencyKey);
        }
        log.debug("Replaying stored result for idempotency key {}", idempotencyKey);
        return TransactionResult.replayed(existing);
    }
}
