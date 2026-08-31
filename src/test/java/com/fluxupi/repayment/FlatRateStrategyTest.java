package com.fluxupi.repayment;

import com.fluxupi.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FlatRateStrategyTest {

    private final FlatRateStrategy strategy = new FlatRateStrategy();

    @Test
    @DisplayName("worked example: 12,000 at 18%/yr flat over 12 months")
    void workedExample() {
        RepaymentPlan plan = strategy.generate(new RepaymentTerms(
                Money.of(12_000), new BigDecimal("18.000"), 12, LocalDate.of(2026, 1, 1)));

        // Flat interest: 12,000 * 0.18 * 1 year = 2,160 total.
        assertThat(plan.totalInterest()).isEqualByComparingTo(Money.of(2_160));
        assertThat(plan.totalPayable()).isEqualByComparingTo(Money.of(14_160));
        assertThat(plan.installments()).hasSize(12);

        // Every instalment: 1,000 principal + 180 interest = 1,180.
        plan.installments().forEach(i -> {
            assertThat(i.principalComponent()).isEqualByComparingTo(Money.of(1_000));
            assertThat(i.interestComponent()).isEqualByComparingTo(Money.of(180));
            assertThat(i.totalDue()).isEqualByComparingTo(Money.of(1_180));
        });

        assertThat(plan.installments().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(plan.installments().get(11).dueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @ParameterizedTest
    @CsvSource({
            "10000, 24.000, 6",
            "37777, 19.500, 9",
            "100000, 15.250, 24",
            "500, 30.000, 3",
            "999999, 11.111, 18",
    })
    @DisplayName("components always reconcile to the paisa, whatever the inputs")
    void componentsReconcile(long principal, String rate, int tenure) {
        // The RepaymentPlan constructor throws if the sums do not match exactly,
        // so a passing construction is the assertion. These extra checks make
        // the intent explicit.
        RepaymentPlan plan = strategy.generate(new RepaymentTerms(
                Money.of(principal), new BigDecimal(rate), tenure, LocalDate.of(2026, 3, 1)));

        BigDecimal principalSum = plan.installments().stream()
                .map(InstallmentSpec::principalComponent).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal totalSum = plan.installments().stream()
                .map(InstallmentSpec::totalDue).reduce(Money.ZERO, BigDecimal::add);

        assertThat(principalSum).isEqualByComparingTo(Money.of(principal));
        assertThat(totalSum).isEqualByComparingTo(plan.totalPayable());
        assertThat(plan.installments()).extracting(InstallmentSpec::number)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, tenure).boxed().toList());
    }

    @Test
    @DisplayName("the rounding remainder lands in the final instalment, not spread as dust")
    void remainderGoesToLastInstalment() {
        // 10,000 / 3 does not divide evenly.
        RepaymentPlan plan = strategy.generate(new RepaymentTerms(
                Money.of(10_000), new BigDecimal("12.000"), 3, LocalDate.of(2026, 1, 1)));

        assertThat(plan.installments().get(0).principalComponent()).isEqualByComparingTo(new BigDecimal("3333.33"));
        assertThat(plan.installments().get(1).principalComponent()).isEqualByComparingTo(new BigDecimal("3333.33"));
        assertThat(plan.installments().get(2).principalComponent()).isEqualByComparingTo(new BigDecimal("3333.34"));
    }

    @Test
    @DisplayName("type() identifies the strategy for the factory")
    void reportsType() {
        assertThat(strategy.type()).isEqualTo(InterestStrategyType.FLAT_RATE);
    }
}
