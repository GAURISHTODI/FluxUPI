package com.fluxupi.ledger;

import com.fluxupi.AbstractIntegrationTest;
import com.fluxupi.TestDataFactory;
import com.fluxupi.common.Money;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineRepository;
import com.fluxupi.transaction.SpendCommand;
import com.fluxupi.transaction.Transaction;
import com.fluxupi.transaction.TransactionRepository;
import com.fluxupi.transaction.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project's headline claim, tested at volume: after 1,000+ mixed
 * transactions, the books balance exactly — globally, per transaction, and
 * against the credit line balances they are supposed to explain.
 *
 * <p>The workload is deliberately messy. It mixes spends, reversals and
 * repayments, sends duplicate idempotency keys, and attempts spends that must
 * be refused, because a ledger that only balances on the happy path is not
 * worth much. Amounts are drawn from a seeded RNG so a failure can be
 * reproduced from the printed seed.
 */
class LedgerReconciliationTest extends AbstractIntegrationTest {

    private static final int TARGET_TRANSACTIONS = 1_200;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CreditLineRepository creditLineRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private TestDataFactory testData;

    @Test
    @DisplayName("1,200 mixed transactions leave debits exactly equal to credits")
    void ledgerReconcilesAfterAHighVolumeMixedWorkload() {
        // Limits are set well above what the workload can draw down, so a
        // legitimate spend is never starved. The refusal path is exercised
        // explicitly below with a deliberately oversized amount.
        List<CreditLine> lines = List.of(
                testData.persistActiveCreditLine(Money.of(5_000_000)),
                testData.persistActiveCreditLine(Money.of(5_000_000)),
                testData.persistActiveCreditLine(Money.of(5_000_000)));

        // Fixed seed: a failure here must be reproducible, not a one-off.
        long seed = 20260831L;
        Random random = new Random(seed);

        int settled = 0;
        int refused = 0;
        int replayed = 0;

        for (int i = 0; i < TARGET_TRANSACTIONS; i++) {
            CreditLine line = lines.get(random.nextInt(lines.size()));
            int roll = random.nextInt(100);

            try {
                if (roll < 60) {
                    // Ordinary spend.
                    transactionService.spend(spendOn(line, Money.of(random.nextInt(1, 5_000))));
                    settled++;
                } else if (roll < 75) {
                    // Repayment with an interest component, so more than two
                    // ledger lines have to balance.
                    BigDecimal principal = Money.of(random.nextInt(1, 2_000));
                    BigDecimal interest = Money.of(random.nextInt(0, 200));
                    transactionService.repay(line.getId(), principal, interest, Money.ZERO,
                            "bulk repayment", UUID.randomUUID().toString());
                    settled++;
                } else if (roll < 85) {
                    // A duplicate: the same command sent twice.
                    SpendCommand command = spendOn(line, Money.of(random.nextInt(1, 1_000)));
                    transactionService.spend(command);
                    if (transactionService.spend(command).replayed()) {
                        replayed++;
                    }
                    settled++;
                } else if (roll < 95) {
                    // A spend then its reversal.
                    var original = transactionService.spend(spendOn(line, Money.of(random.nextInt(1, 3_000))));
                    transactionService.reverse(original.transaction().getId(), "customer dispute",
                            UUID.randomUUID().toString());
                    settled += 2;
                } else {
                    // A spend that must be refused: no ledger entries at all.
                    try {
                        transactionService.spend(spendOn(line, Money.of(10_000_000)));
                    } catch (RuntimeException expected) {
                        refused++;
                    }
                }
            } catch (com.fluxupi.common.exception.InsufficientCreditLimitException starved) {
                // A genuine refusal: the line ran out of headroom for this
                // draw. It writes no ledger entries, so it cannot unbalance the
                // book — count it and carry on rather than failing the run.
                refused++;
            } catch (RuntimeException unexpected) {
                throw new AssertionError("Workload iteration " + i + " failed unexpectedly (seed " + seed + ")",
                        unexpected);
            }
        }

        LedgerService.ReconciliationReport report = ledgerService.reconcile();

        assertThat(report.entryCount())
                .as("the workload should have produced a substantial book")
                .isGreaterThan(2_000);
        assertThat(report.totalDebits())
                .as("every rupee debited must be credited somewhere (seed %d)", seed)
                .isEqualByComparingTo(report.totalCredits());
        assertThat(report.imbalancedTransactionIds())
                .as("no single transaction may be lopsided")
                .isEmpty();
        assertThat(report.malformedTransactionIds())
                .as("no transaction may have a one-sided journal entry")
                .isEmpty();
        assertThat(report.isBalanced()).isTrue();
        assertThat(report.difference()).isEqualByComparingTo(Money.ZERO);

        assertThat(settled).isGreaterThan(1_000);
        assertThat(refused).as("the refusal path should have been exercised").isPositive();
        assertThat(replayed).as("the replay path should have been exercised").isPositive();
    }

    @Test
    @DisplayName("each credit line's utilisation equals its net CUSTOMER_RECEIVABLE position")
    void creditLineBalancesAgreeWithTheLedger() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(100_000));

        transactionService.spend(spendOn(line, Money.of(12_000)));
        var reversible = transactionService.spend(spendOn(line, Money.of(3_000)));
        transactionService.spend(spendOn(line, Money.of(5_500)));
        transactionService.reverse(reversible.transaction().getId(), "duplicate charge",
                UUID.randomUUID().toString());
        transactionService.repay(line.getId(), Money.of(2_000), Money.of(180), Money.ZERO,
                "first EMI", UUID.randomUUID().toString());

        // 12,000 + 5,500 spent, 2,000 repaid (the 3,000 was reversed) = 15,500 outstanding.
        BigDecimal expectedOutstanding = Money.of(15_500);

        CreditLine reloaded = creditLineRepository.findById(line.getId()).orElseThrow();
        assertThat(reloaded.getUtilizedLimit()).isEqualByComparingTo(expectedOutstanding);

        BigDecimal receivable = ledgerEntryRepository
                .netDebitFor(line.getId(), LedgerAccount.CUSTOMER_RECEIVABLE);
        assertThat(receivable)
                .as("the ledger must independently agree with the credit line's own balance")
                .isEqualByComparingTo(expectedOutstanding);

        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("every settled transaction has at least two entries that net to zero")
    void everyTransactionIsAProperJournalEntry() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(20_000));
        transactionService.spend(spendOn(line, Money.of(1_000)));
        transactionService.repay(line.getId(), Money.of(500), Money.of(45), Money.of(10),
                "EMI with fee", UUID.randomUUID().toString());

        List<Transaction> transactions = transactionRepository
                .findAllByCreditLineIdOrderByCreatedAtAsc(line.getId());
        assertThat(transactions).isNotEmpty();

        for (Transaction transaction : transactions) {
            List<LedgerEntry> entries = ledgerEntryRepository
                    .findAllByTransactionIdOrderByEntrySeqAsc(transaction.getId());

            assertThat(entries).as("transaction %s", transaction.getId()).hasSizeGreaterThanOrEqualTo(2);
            BigDecimal net = entries.stream()
                    .map(LedgerEntry::signedAmount)
                    .reduce(Money.ZERO, BigDecimal::add);
            assertThat(net).as("transaction %s must net to zero", transaction.getId())
                    .isEqualByComparingTo(Money.ZERO);
            assertThat(entries).extracting(LedgerEntry::getEntrySeq)
                    .as("entry sequence numbers are dense and start at zero")
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, entries.size())
                            .boxed().toList());
        }

        // The three-line repayment specifically: cash in, principal + interest + fee out.
        Transaction repayment = transactions.stream()
                .filter(t -> t.getType() == com.fluxupi.transaction.TransactionType.REPAYMENT)
                .findFirst().orElseThrow();
        List<LedgerEntry> repaymentEntries = ledgerEntryRepository
                .findAllByTransactionIdOrderByEntrySeqAsc(repayment.getId());
        assertThat(repaymentEntries).hasSize(4);
        assertThat(repaymentEntries).extracting(LedgerEntry::getAccount)
                .containsExactly(LedgerAccount.SETTLEMENT_CASH, LedgerAccount.CUSTOMER_RECEIVABLE,
                        LedgerAccount.INTEREST_INCOME, LedgerAccount.FEE_INCOME);
    }

    private SpendCommand spendOn(CreditLine line, BigDecimal amount) {
        return new SpendCommand(line.getId(), amount, "merchant@fluxbank",
                "reconciliation workload", UUID.randomUUID().toString());
    }
}
