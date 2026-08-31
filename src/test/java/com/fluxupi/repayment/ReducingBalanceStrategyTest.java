package com.fluxupi.repayment;

import com.fluxupi.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReducingBalanceStrategyTest {

    private final ReducingBalanceStrategy strategy = new ReducingBalanceStrategy();

    @Test
    @DisplayName("worked example: 100,000 at 12%/yr reducing over 12 months, EMI ~ 8,884.88")
    void workedExample() {
        RepaymentPlan plan = strategy.generate(new RepaymentTerms(
                Money.of(100_000), new BigDecimal("12.000"), 12, LocalDate.of(2026, 1, 1)));

        assertThat(plan.installments()).hasSize(12);

        // Standard amortisation: first instalment carries the most interest
        // (1% of 100,000 = 1,000), the last carries the least.
        assertThat(plan.installments().get(0).interestComponent()).isEqualByComparingTo(Money.of(1_000));
        assertThat(plan.installments().get(0).interestComponent())
                .isGreaterThan(plan.installments().get(11).interestComponent());

        // Total interest on a 12%/12mo reducing loan of 1 lakh is ~ 6,618.55.
        assertThat(plan.totalInterest()).isBetween(new BigDecimal("6600.00"), new BigDecimal("6650.00"));

        // The non-final EMIs are equal to the paisa.
        BigDecimal emi = plan.installments().get(0).totalDue();
        for (int i = 0; i < 11; i++) {
            assertThat(plan.installments().get(i).totalDue())
                    .as("EMI %d", i + 1)
                    .isEqualByComparingTo(emi);
        }
    }

    @Test
    @DisplayName("the outstanding balance lands on exactly zero after the final instalment")
    void balanceClosesToZero() {
        RepaymentPlan plan = strategy.generate(new RepaymentTerms(
                Money.of(73_337), new BigDecimal("17.750"), 7, LocalDate.of(2026, 1, 1)));

        BigDecimal principalRepaid = plan.installments().stream()
                .map(InstallmentSpec::principalComponent)
                .reduce(Money.ZERO, BigDecimal::add);
        assertThat(principalRepaid).isEqualByComparingTo(Money.of(73_337));
    }

    @Test
    @DisplayName("a zero interest rate degrades to an even principal split")
    void zeroRateIsEvenSplit() {
        RepaymentPlan plan = strategy.generate(new RepaymentTerms(
                Money.of(12_000), BigDecimal.ZERO, 12, LocalDate.of(2026, 1, 1)));

        assertThat(plan.totalInterest()).isEqualByComparingTo(Money.ZERO);
        plan.installments().forEach(i ->
                assertThat(i.totalDue()).isEqualByComparingTo(Money.of(1_000)));
    }

    @ParameterizedTest
    @CsvSource({
            "10000, 24.000, 6",
            "37777, 19.500, 9",
            "100000, 15.250, 24",
            "500, 30.000, 3",
            "999999, 11.111, 18",
            "250000, 0.000, 10",
    })
    @DisplayName("components reconcile to the paisa across a range of inputs")
    void componentsReconcile(long principal, String rate, int tenure) {
        RepaymentPlan plan = strategy.generate(new RepaymentTerms(
                Money.of(principal), new BigDecimal(rate), tenure, LocalDate.of(2026, 3, 1)));

        BigDecimal principalSum = plan.installments().stream()
                .map(InstallmentSpec::principalComponent).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal interestSum = plan.installments().stream()
                .map(InstallmentSpec::interestComponent).reduce(Money.ZERO, BigDecimal::add);

        assertThat(principalSum).isEqualByComparingTo(Money.of(principal));
        assertThat(interestSum).isEqualByComparingTo(plan.totalInterest());
        plan.installments().forEach(i ->
                assertThat(i.principalComponent()).isGreaterThanOrEqualTo(Money.ZERO));
    }

    @Test
    @DisplayName("a flat and a reducing loan on the same terms: flat always costs more")
    void flatCostsMoreThanReducing() {
        RepaymentTerms terms = new RepaymentTerms(
                Money.of(60_000), new BigDecimal("20.000"), 12, LocalDate.of(2026, 1, 1));

        BigDecimal flatInterest = new FlatRateStrategy().generate(terms).totalInterest();
        BigDecimal reducingInterest = strategy.generate(terms).totalInterest();

        assertThat(flatInterest).isGreaterThan(reducingInterest);
    }

    @Test
    void reportsType() {
        assertThat(strategy.type()).isEqualTo(InterestStrategyType.REDUCING_BALANCE);
    }
}
