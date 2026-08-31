package com.fluxupi.repayment;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.RepaymentException;
import com.fluxupi.common.exception.ResourceNotFoundException;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineRepository;
import com.fluxupi.ledger.Journal;
import com.fluxupi.ledger.LedgerService;
import com.fluxupi.transaction.Transaction;
import com.fluxupi.transaction.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The single atomic transaction behind a scheduled repayment.
 *
 * <p>Separated from {@link RepaymentService} for the same reason
 * {@code TransactionExecutor} is separate from {@code TransactionService}: when
 * the idempotency-key unique constraint rejects a concurrent duplicate, this
 * transaction is already doomed, so the "read back the winner" step must run in
 * a <em>fresh</em> transaction owned by the caller.
 *
 * <p>Lock order is credit line first, then schedule — the same order every
 * other writer uses, so a repayment and a spend on the same line cannot
 * deadlock.
 */
@Component
public class RepaymentExecutor {

    private static final Logger log = LoggerFactory.getLogger(RepaymentExecutor.class);

    private final CreditLineRepository creditLineRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final Clock clock;

    public RepaymentExecutor(CreditLineRepository creditLineRepository,
                             RepaymentScheduleRepository scheduleRepository,
                             TransactionRepository transactionRepository,
                             LedgerService ledgerService,
                             Clock clock) {
        this.creditLineRepository = creditLineRepository;
        this.scheduleRepository = scheduleRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
        this.clock = clock;
    }

    @Transactional
    public Transaction execute(UUID creditLineId, BigDecimal payment, String idempotencyKey, String fingerprint) {
        CreditLine creditLine = creditLineRepository.findByIdForUpdate(creditLineId)
                .orElseThrow(() -> new ResourceNotFoundException("CreditLine", creditLineId));
        RepaymentSchedule schedule = scheduleRepository.findActiveForUpdate(creditLineId)
                .orElseThrow(() -> new RepaymentException(
                        "Credit line " + creditLineId + " has no active repayment schedule; generate one first"));

        LocalDate today = LocalDate.now(clock);
        schedule.refreshAsOf(today);

        BigDecimal remaining = payment;
        BigDecimal principalTotal = Money.ZERO;
        BigDecimal interestTotal = Money.ZERO;

        while (Money.isPositive(remaining)) {
            Optional<Installment> next = schedule.nextPayable();
            if (next.isEmpty()) {
                break;
            }
            Installment.RepaymentAllocation allocation = next.get().applyPayment(remaining);
            if (Money.isZero(allocation.total())) {
                break;
            }
            principalTotal = principalTotal.add(allocation.principal());
            interestTotal = interestTotal.add(allocation.interest());
            remaining = Money.normalize(remaining.subtract(allocation.total()));
        }

        principalTotal = Money.normalize(principalTotal);
        interestTotal = Money.normalize(interestTotal);
        BigDecimal absorbed = Money.normalize(principalTotal.add(interestTotal));
        if (!Money.isPositive(absorbed)) {
            throw new RepaymentException("Nothing left to repay on credit line " + creditLineId);
        }
        if (Money.isGreaterThan(payment, absorbed)) {
            log.debug("Repayment of {} exceeded outstanding {}; only the outstanding amount was taken",
                    payment, absorbed);
        }

        Transaction repayment = Transaction.repayment(creditLine, absorbed, idempotencyKey,
                fingerprint, "Scheduled repayment on " + today);
        transactionRepository.saveAndFlush(repayment);
        ledgerService.post(repayment, Journal.forRepayment(principalTotal, interestTotal, Money.ZERO));
        repayment.markSuccess();
        transactionRepository.save(repayment);

        if (Money.isPositive(principalTotal)) {
            creditLine.restoreLimit(principalTotal);
        }
        schedule.refreshAsOf(today);
        schedule.markSettledIfComplete();

        creditLineRepository.save(creditLine);
        scheduleRepository.save(schedule);

        log.debug("Applied repayment {} to credit line {}: {} principal / {} interest, {} outstanding",
                repayment.getId(), creditLineId, principalTotal, interestTotal, schedule.outstandingAmount());
        return repayment;
    }
}
