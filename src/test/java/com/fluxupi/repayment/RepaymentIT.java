package com.fluxupi.repayment;

import com.fluxupi.AbstractIntegrationTest;
import com.fluxupi.TestDataFactory;
import com.fluxupi.common.Money;
import com.fluxupi.common.exception.RepaymentException;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineRepository;
import com.fluxupi.ledger.LedgerAccount;
import com.fluxupi.ledger.LedgerEntryRepository;
import com.fluxupi.ledger.LedgerService;
import com.fluxupi.transaction.SpendCommand;
import com.fluxupi.transaction.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepaymentIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private RepaymentService repaymentService;

    @Autowired
    private CreditLineRepository creditLineRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private TestDataFactory testData;

    private CreditLine drawnLine(BigDecimal limit, BigDecimal spend) {
        CreditLine line = testData.persistActiveCreditLine(limit);
        transactionService.spend(new SpendCommand(line.getId(), spend, "merchant@fluxbank",
                "opening draw", UUID.randomUUID().toString()));
        return creditLineRepository.findById(line.getId()).orElseThrow();
    }

    @Test
    @DisplayName("a schedule is generated for exactly the outstanding principal")
    void scheduleCoversOutstandingPrincipal() {
        CreditLine line = drawnLine(Money.of(100_000), Money.of(24_000));

        RepaymentSchedule schedule = repaymentService.generateSchedule(line.getId());

        assertThat(schedule.getPrincipal()).isEqualByComparingTo(Money.of(24_000));
        assertThat(schedule.getInstallments()).hasSize(line.getTenureMonths());
        assertThat(schedule.getStatus()).isEqualTo(RepaymentScheduleStatus.ACTIVE);
    }

    @Test
    @DisplayName("generating a new schedule supersedes the previous one")
    void regeneratingSupersedes() {
        CreditLine line = drawnLine(Money.of(100_000), Money.of(10_000));
        RepaymentSchedule first = repaymentService.generateSchedule(line.getId());

        transactionService.spend(new SpendCommand(line.getId(), Money.of(5_000), "merchant@fluxbank",
                "more", UUID.randomUUID().toString()));
        RepaymentSchedule second = repaymentService.generateSchedule(line.getId());

        assertThat(repaymentService.scheduleHistory(line.getId()))
                .filteredOn(s -> s.getId().equals(first.getId()))
                .allMatch(s -> s.getStatus() == RepaymentScheduleStatus.SUPERSEDED);
        assertThat(second.getPrincipal()).isEqualByComparingTo(Money.of(15_000));
    }

    @Test
    @DisplayName("paying instalments settles interest and principal, and only principal restores headroom")
    void payingInstalmentsRestoresPrincipalHeadroomOnly() {
        CreditLine line = drawnLine(Money.of(100_000), Money.of(30_000));
        assertThat(line.getAvailableLimit()).isEqualByComparingTo(Money.of(70_000));

        RepaymentSchedule schedule = repaymentService.generateSchedule(line.getId());
        BigDecimal firstInterest = schedule.getInstallments().get(0).getInterestComponent();
        BigDecimal firstPrincipal = schedule.getInstallments().get(0).getPrincipalComponent();

        repaymentService.payNextInstallment(line.getId(), UUID.randomUUID().toString());

        CreditLine reloaded = creditLineRepository.findById(line.getId()).orElseThrow();
        assertThat(reloaded.getAvailableLimit())
                .as("headroom goes up by the principal component only, not the interest")
                .isEqualByComparingTo(Money.of(70_000).add(firstPrincipal));

        assertThat(ledgerEntryRepository.netDebitFor(line.getId(), LedgerAccount.INTEREST_INCOME))
                .as("interest income is recognised as a credit")
                .isEqualByComparingTo(firstInterest.negate());
        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("paying the whole schedule down settles it and clears the outstanding principal")
    void payingEverythingSettlesTheScheduleAndTheLine() {
        CreditLine line = drawnLine(Money.of(100_000), Money.of(12_000));
        RepaymentSchedule schedule = repaymentService.generateSchedule(line.getId());
        int installments = schedule.getInstallments().size();

        for (int i = 0; i < installments; i++) {
            repaymentService.payNextInstallment(line.getId(), UUID.randomUUID().toString());
        }

        RepaymentSchedule settled = repaymentService.scheduleHistory(line.getId()).get(0);
        assertThat(settled.getStatus()).isEqualTo(RepaymentScheduleStatus.SETTLED);
        assertThat(settled.outstandingAmount()).isEqualByComparingTo(Money.ZERO);

        CreditLine reloaded = creditLineRepository.findById(line.getId()).orElseThrow();
        assertThat(reloaded.getUtilizedLimit())
                .as("all principal repaid -> nothing drawn")
                .isEqualByComparingTo(Money.ZERO);
        assertThat(reloaded.getAvailableLimit()).isEqualByComparingTo(Money.of(100_000));
        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("over-paying takes only what is outstanding and leaves the schedule settled")
    void overpaymentTakesOnlyWhatIsOwed() {
        CreditLine line = drawnLine(Money.of(100_000), Money.of(6_000));
        RepaymentSchedule schedule = repaymentService.generateSchedule(line.getId());
        BigDecimal totalPayable = schedule.totalPayable();

        var result = repaymentService.pay(line.getId(), totalPayable.add(Money.of(50_000)),
                UUID.randomUUID().toString());

        assertThat(result.transaction().getAmount()).isEqualByComparingTo(totalPayable);
        assertThat(repaymentService.scheduleHistory(line.getId()).get(0).getStatus())
                .isEqualTo(RepaymentScheduleStatus.SETTLED);
        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("a repayment is idempotent on its key")
    void repaymentIsIdempotent() {
        CreditLine line = drawnLine(Money.of(100_000), Money.of(20_000));
        repaymentService.generateSchedule(line.getId());
        String key = "repay-" + UUID.randomUUID();

        var first = repaymentService.pay(line.getId(), Money.of(3_000), key);
        var replay = repaymentService.pay(line.getId(), Money.of(3_000), key);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transaction().getId()).isEqualTo(first.transaction().getId());
        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("two concurrent payments with the same key charge the borrower once")
    void concurrentRepaymentsWithOneKeyChargeOnce() throws Exception {
        CreditLine line = drawnLine(Money.of(100_000), Money.of(40_000));
        repaymentService.generateSchedule(line.getId());
        String key = "repay-" + UUID.randomUUID();

        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<UUID>> futures = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(i -> pool.submit(() ->
                            repaymentService.pay(line.getId(), Money.of(2_500), key).transaction().getId()))
                    .toList();

            List<UUID> ids = futures.stream().map(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).distinct().toList();

            assertThat(ids).hasSize(1);
        }

        assertThat(ledgerService.reconcile().isBalanced()).isTrue();
    }

    @Test
    @DisplayName("repaying with no schedule is a clear error")
    void repayingWithoutAScheduleFails() {
        CreditLine line = drawnLine(Money.of(100_000), Money.of(5_000));

        assertThatThrownBy(() -> repaymentService.pay(line.getId(), Money.of(1_000), UUID.randomUUID().toString()))
                .isInstanceOf(RepaymentException.class)
                .hasMessageContaining("no active repayment schedule");
    }

    @Test
    @DisplayName("generating a schedule with nothing drawn is rejected")
    void scheduleWithNoPrincipalRejected() {
        CreditLine line = testData.persistActiveCreditLine(Money.of(50_000));

        assertThatThrownBy(() -> repaymentService.generateSchedule(line.getId()))
                .isInstanceOf(RepaymentException.class)
                .hasMessageContaining("no outstanding principal");
    }
}
