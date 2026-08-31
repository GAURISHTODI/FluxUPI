package com.fluxupi.repayment;

import com.fluxupi.common.Fingerprint;
import com.fluxupi.common.Money;
import com.fluxupi.common.exception.DuplicateIdempotencyKeyException;
import com.fluxupi.common.exception.RepaymentException;
import com.fluxupi.common.exception.ResourceNotFoundException;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineRepository;
import com.fluxupi.notification.DomainEventPublisher;
import com.fluxupi.notification.TransactionSettledEvent;
import com.fluxupi.transaction.Transaction;
import com.fluxupi.transaction.TransactionRepository;
import com.fluxupi.transaction.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates repayment schedules and applies payments against them.
 *
 * <p><b>Generating.</b> A schedule covers the principal currently drawn on the
 * credit line ({@code approvedLimit − availableLimit}). Generating a new one
 * supersedes the previous — schedules are immutable snapshots, not living
 * documents.
 *
 * <p><b>Paying.</b> A payment is allocated across instalments oldest-first,
 * interest before principal within each. The resulting principal / interest
 * split is what gets posted to the ledger and what determines how much
 * headroom is returned to the line — paying interest buys back nothing. The
 * actual database work is one atomic transaction in {@link RepaymentExecutor};
 * this class owns only idempotency and event publication, mirroring the
 * {@code TransactionService} / {@code TransactionExecutor} split.
 */
@Service
public class RepaymentService {

    private static final Logger log = LoggerFactory.getLogger(RepaymentService.class);

    private final RepaymentExecutor executor;
    private final CreditLineRepository creditLineRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final TransactionRepository transactionRepository;
    private final InterestStrategyFactory strategyFactory;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    public RepaymentService(RepaymentExecutor executor,
                            CreditLineRepository creditLineRepository,
                            RepaymentScheduleRepository scheduleRepository,
                            TransactionRepository transactionRepository,
                            InterestStrategyFactory strategyFactory,
                            DomainEventPublisher eventPublisher,
                            Clock clock) {
        this.executor = executor;
        this.creditLineRepository = creditLineRepository;
        this.scheduleRepository = scheduleRepository;
        this.transactionRepository = transactionRepository;
        this.strategyFactory = strategyFactory;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // ------------------------------------------------------------- generation

    @Transactional
    public RepaymentSchedule generateSchedule(UUID creditLineId) {
        CreditLine creditLine = creditLineRepository.findByIdForUpdate(creditLineId)
                .orElseThrow(() -> new ResourceNotFoundException("CreditLine", creditLineId));

        BigDecimal principal = creditLine.getUtilizedLimit();
        if (!Money.isPositive(principal)) {
            throw new RepaymentException(
                    "Credit line %s has no outstanding principal to schedule".formatted(creditLineId));
        }

        // Supersede and flush the old schedule *before* inserting the new one:
        // the "one active schedule per line" partial unique index is checked at
        // statement time, and Hibernate would otherwise order the INSERT ahead
        // of the status UPDATE and collide with it.
        scheduleRepository.findActiveForUpdate(creditLineId).ifPresent(existing -> {
            existing.supersede();
            scheduleRepository.saveAndFlush(existing);
        });

        RepaymentTerms terms = new RepaymentTerms(
                principal,
                creditLine.getAnnualInterestRatePercent(),
                creditLine.getTenureMonths(),
                LocalDate.now(clock).plusMonths(1));

        RepaymentPlan plan = strategyFactory.forType(creditLine.getInterestStrategy()).generate(terms);
        RepaymentSchedule schedule = scheduleRepository.save(
                RepaymentSchedule.from(creditLine, creditLine.getInterestStrategy(), plan));

        log.debug("Generated {}-month {} schedule for credit line {}: principal {}, interest {}",
                plan.tenureMonths(), creditLine.getInterestStrategy(), creditLineId,
                plan.principal(), plan.totalInterest());
        return schedule;
    }

    @Transactional(readOnly = true)
    public RepaymentSchedule currentSchedule(UUID creditLineId) {
        return scheduleRepository.findActive(creditLineId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Active RepaymentSchedule for credit line", creditLineId));
    }

    @Transactional(readOnly = true)
    public List<RepaymentSchedule> scheduleHistory(UUID creditLineId) {
        return scheduleRepository.findAllByCreditLineIdOrderByGeneratedAtDesc(creditLineId);
    }

    @Transactional
    public RepaymentSchedule refreshSchedule(UUID creditLineId) {
        RepaymentSchedule schedule = scheduleRepository.findActiveForUpdate(creditLineId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Active RepaymentSchedule for credit line", creditLineId));
        schedule.refreshAsOf(LocalDate.now(clock));
        return scheduleRepository.save(schedule);
    }

    // ---------------------------------------------------------------- payment

    /** Pays a specific amount, allocated oldest-instalment-first. */
    public TransactionResult pay(UUID creditLineId, BigDecimal amount, String idempotencyKey) {
        BigDecimal payment = Money.normalize(amount);
        if (payment == null || !Money.isPositive(payment)) {
            throw new RepaymentException("Repayment amount must be positive");
        }
        return applyPayment(creditLineId, payment, idempotencyKey);
    }

    /** Pays exactly the outstanding amount of the next unpaid instalment. */
    public TransactionResult payNextInstallment(UUID creditLineId, String idempotencyKey) {
        RepaymentSchedule schedule = scheduleRepository.findActive(creditLineId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Active RepaymentSchedule for credit line", creditLineId));
        BigDecimal due = schedule.nextPayable()
                .orElseThrow(() -> new RepaymentException(
                        "Schedule for credit line " + creditLineId + " is fully paid"))
                .outstandingAmount();
        return applyPayment(creditLineId, due, idempotencyKey);
    }

    private TransactionResult applyPayment(UUID creditLineId, BigDecimal payment, String idempotencyKey) {
        String fingerprint = Fingerprint.of("SCHEDULED_REPAYMENT", creditLineId, payment);

        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, idempotencyKey);
        }

        try {
            Transaction settled = executor.execute(creditLineId, payment, idempotencyKey, fingerprint);
            eventPublisher.publish(TransactionSettledEvent.from(settled));
            return TransactionResult.processed(settled);
        } catch (DataIntegrityViolationException race) {
            // Lost the idempotency-key race. Our transaction rolled back and
            // wrote nothing; the winner's row is authoritative. This read runs
            // in a new transaction, so the earlier rollback does not poison it.
            Transaction winner = transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> race);
            return replay(winner, fingerprint, idempotencyKey);
        }
    }

    private TransactionResult replay(Transaction existing, String fingerprint, String idempotencyKey) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new DuplicateIdempotencyKeyException(idempotencyKey);
        }
        return TransactionResult.replayed(existing);
    }
}
