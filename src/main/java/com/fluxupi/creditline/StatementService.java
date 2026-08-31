package com.fluxupi.creditline;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.ResourceNotFoundException;
import com.fluxupi.ledger.LedgerAccount;
import com.fluxupi.ledger.LedgerEntry;
import com.fluxupi.ledger.LedgerEntryRepository;
import com.fluxupi.repayment.RepaymentSchedule;
import com.fluxupi.repayment.RepaymentScheduleRepository;
import com.fluxupi.transaction.Transaction;
import com.fluxupi.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Assembles a read-only statement for a credit line from the three sources that
 * each hold part of the picture: the line itself (limits), the transaction log
 * (what happened), and the ledger (the independent double-entry view).
 *
 * <p>The statement deliberately reports the outstanding principal twice — once
 * from {@code CreditLine.utilizedLimit} and once from the ledger's
 * {@code CUSTOMER_RECEIVABLE} balance — so a reader can see the two agree.
 */
@Service
public class StatementService {

    private final CreditLineRepository creditLineRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final RepaymentScheduleRepository scheduleRepository;

    public StatementService(CreditLineRepository creditLineRepository,
                            TransactionRepository transactionRepository,
                            LedgerEntryRepository ledgerEntryRepository,
                            RepaymentScheduleRepository scheduleRepository) {
        this.creditLineRepository = creditLineRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Transactional(readOnly = true)
    public Statement forCreditLine(UUID creditLineId) {
        CreditLine line = creditLineRepository.findById(creditLineId)
                .orElseThrow(() -> new ResourceNotFoundException("CreditLine", creditLineId));

        List<Transaction> transactions =
                transactionRepository.findAllByCreditLineIdOrderByCreatedAtAsc(creditLineId);
        List<LedgerEntry> ledgerEntries =
                ledgerEntryRepository.findAllByCreditLineIdOrderByCreatedAtAscEntrySeqAsc(creditLineId);

        BigDecimal ledgerReceivable = ledgerEntryRepository
                .netDebitFor(creditLineId, LedgerAccount.CUSTOMER_RECEIVABLE);

        BigDecimal settledSpends = transactionRepository.sumSettledSpends(creditLineId);
        BigDecimal settledRepayments = transactionRepository.sumSettledRepayments(creditLineId);

        RepaymentSchedule schedule = scheduleRepository.findActive(creditLineId).orElse(null);

        return new Statement(
                line.getId(),
                line.getUser().getId(),
                line.getLender().getCode(),
                line.getStatus(),
                Money.normalize(line.getApprovedLimit()),
                Money.normalize(line.getAvailableLimit()),
                Money.normalize(line.getUtilizedLimit()),
                Money.normalize(ledgerReceivable),
                Money.isEqual(line.getUtilizedLimit(), ledgerReceivable),
                Money.normalize(settledSpends),
                Money.normalize(settledRepayments),
                transactions.stream().map(StatementLine::from).toList(),
                ledgerEntries.stream().map(LedgerLine::from).toList(),
                schedule == null ? null : ScheduleSummary.from(schedule),
                Instant.now());
    }

    public record Statement(UUID creditLineId, UUID userId, String lenderCode, CreditLineStatus status,
                            BigDecimal approvedLimit, BigDecimal availableLimit, BigDecimal outstandingPrincipal,
                            BigDecimal ledgerReceivableBalance, boolean balancesAgree,
                            BigDecimal lifetimeSpends, BigDecimal lifetimeRepayments,
                            List<StatementLine> transactions, List<LedgerLine> ledgerEntries,
                            ScheduleSummary activeSchedule, Instant generatedAt) {
    }

    public record StatementLine(UUID id, String type, String status, BigDecimal amount,
                                String payeeVpa, String description, Instant at) {
        static StatementLine from(Transaction t) {
            return new StatementLine(t.getId(), t.getType().name(), t.getStatus().name(), t.getAmount(),
                    t.getPayeeVpa(), t.getDescription(), t.getCreatedAt());
        }
    }

    public record LedgerLine(UUID transactionId, String account, String direction,
                             BigDecimal amount, String narrative, Instant at) {
        static LedgerLine from(LedgerEntry e) {
            return new LedgerLine(e.getTransactionId(), e.getAccount().name(), e.getDirection().name(),
                    e.getAmount(), e.getNarrative(), e.getCreatedAt());
        }
    }

    public record ScheduleSummary(UUID id, String status, BigDecimal principal, BigDecimal totalInterest,
                                  BigDecimal outstanding, int installmentCount) {
        static ScheduleSummary from(RepaymentSchedule s) {
            return new ScheduleSummary(s.getId(), s.getStatus().name(), s.getPrincipal(), s.getTotalInterest(),
                    s.outstandingAmount(), s.getInstallments().size());
        }
    }
}
