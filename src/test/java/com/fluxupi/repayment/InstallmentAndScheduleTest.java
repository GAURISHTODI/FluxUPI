package com.fluxupi.repayment;

import com.fluxupi.TestFixtures;
import com.fluxupi.common.Money;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.creditline.CreditLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Instalment payment allocation and schedule roll-over, without a database.
 */
class InstallmentAndScheduleTest {

    private final ReducingBalanceStrategy strategy = new ReducingBalanceStrategy();

    private RepaymentSchedule schedule(BigDecimal principal, int tenure) {
        CreditLine line = TestFixtures.activeCreditLine(Money.of(1_000_000));
        line.authorizeSpend(principal);
        RepaymentPlan plan = strategy.generate(new RepaymentTerms(
                principal, new BigDecimal("18.000"), tenure, LocalDate.of(2026, 1, 1)));
        return RepaymentSchedule.from(line, InterestStrategyType.REDUCING_BALANCE, plan);
    }

    @Test
    @DisplayName("a payment is allocated interest-first, then principal")
    void paymentAllocatesInterestBeforePrincipal() {
        RepaymentSchedule s = schedule(Money.of(12_000), 12);
        Installment first = s.nextPayable().orElseThrow();
        BigDecimal interestDue = first.getInterestComponent();

        // Pay just the interest portion.
        Installment.RepaymentAllocation alloc = first.applyPayment(interestDue);

        assertThat(alloc.interest()).isEqualByComparingTo(interestDue);
        assertThat(alloc.principal()).isEqualByComparingTo(Money.ZERO);
        assertThat(first.isFullyPaid()).isFalse();
        assertThat(first.getStatus()).isEqualTo(InstallmentStatus.UPCOMING);
    }

    @Test
    @DisplayName("paying the full amount settles the instalment and marks it PAID")
    void fullPaymentSettlesInstalment() {
        RepaymentSchedule s = schedule(Money.of(12_000), 12);
        Installment first = s.nextPayable().orElseThrow();

        Installment.RepaymentAllocation alloc = first.applyPayment(first.totalDue());

        assertThat(alloc.total()).isEqualByComparingTo(first.totalDue());
        assertThat(first.isFullyPaid()).isTrue();
        assertThat(first.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(first.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("overpaying an instalment only absorbs what was outstanding")
    void overpaymentAbsorbsOnlyOutstanding() {
        RepaymentSchedule s = schedule(Money.of(12_000), 12);
        Installment first = s.nextPayable().orElseThrow();
        BigDecimal outstanding = first.outstandingAmount();

        Installment.RepaymentAllocation alloc = first.applyPayment(outstanding.add(Money.of(5_000)));

        assertThat(alloc.total()).isEqualByComparingTo(outstanding);
        assertThat(first.getPaidAmount()).isEqualByComparingTo(first.totalDue());
    }

    @Test
    @DisplayName("a paid instalment can never be reopened")
    void paidInstalmentIsTerminal() {
        RepaymentSchedule s = schedule(Money.of(6_000), 3);
        Installment first = s.nextPayable().orElseThrow();
        first.applyPayment(first.totalDue());

        org.assertj.core.api.Assertions.assertThatCode(() -> first.refreshStatusAsOf(LocalDate.of(2030, 1, 1)))
                .as("refresh must not attempt to move a PAID instalment")
                .doesNotThrowAnyException();
        assertThat(first.getStatus()).isEqualTo(InstallmentStatus.PAID);
    }

    @Test
    @DisplayName("refreshAsOf rolls instalments UPCOMING -> DUE -> OVERDUE by date")
    void scheduleRefreshRollsStatusForward() {
        RepaymentSchedule s = schedule(Money.of(30_000), 3);

        s.refreshAsOf(LocalDate.of(2025, 12, 15));
        assertThat(s.getInstallments()).allMatch(i -> i.getStatus() == InstallmentStatus.UPCOMING);

        s.refreshAsOf(LocalDate.of(2026, 1, 1));
        assertThat(s.getInstallments().get(0).getStatus()).isEqualTo(InstallmentStatus.DUE);

        s.refreshAsOf(LocalDate.of(2026, 2, 2));
        assertThat(s.getInstallments().get(0).getStatus()).isEqualTo(InstallmentStatus.OVERDUE);
        assertThat(s.getStatus()).isEqualTo(RepaymentScheduleStatus.DELINQUENT);
    }

    @Test
    @DisplayName("a fully paid schedule becomes SETTLED and rejects further transitions")
    void fullyPaidScheduleSettles() {
        RepaymentSchedule s = schedule(Money.of(9_000), 3);
        s.getInstallments().forEach(i -> i.applyPayment(i.totalDue()));
        s.refreshAsOf(LocalDate.of(2026, 4, 1));

        assertThat(s.getStatus()).isEqualTo(RepaymentScheduleStatus.SETTLED);
        assertThatThrownBy(s::supersede).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("outstanding amount tracks payments across the whole schedule")
    void outstandingTracksPayments() {
        RepaymentSchedule s = schedule(Money.of(12_000), 12);
        BigDecimal totalPayable = s.totalPayable();

        Installment first = s.nextPayable().orElseThrow();
        first.applyPayment(first.totalDue());

        assertThat(s.totalPaid()).isEqualByComparingTo(first.totalDue());
        assertThat(s.outstandingAmount()).isEqualByComparingTo(totalPayable.subtract(first.totalDue()));
    }
}
