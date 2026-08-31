package com.fluxupi.underwriting;

import com.fluxupi.TestFixtures;
import com.fluxupi.common.Money;
import com.fluxupi.lender.Lender;
import com.fluxupi.underwriting.rule.ExistingExposureRule;
import com.fluxupi.underwriting.rule.LenderAcceptingApplicationsRule;
import com.fluxupi.underwriting.rule.MinimumIncomeRule;
import com.fluxupi.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule engine, wired with the real rules but no Spring context. The point
 * of these tests is that a decision is fully explained by the numbers on the
 * user and lender rows — no hidden inputs.
 */
class UnderwritingEngineTest {

    private final UnderwritingEngine engine = new UnderwritingEngine(List.of(
            new LenderAcceptingApplicationsRule(),
            new MinimumIncomeRule(),
            new ExistingExposureRule()));

    private Lender lender() {
        return TestFixtures.lenderBuilder()
                .minMonthlyIncome(Money.of(25_000))
                .maxCreditLimit(Money.of(300_000))
                .incomeMultiple(new BigDecimal("2.00"))
                .maxExposureMultiple(new BigDecimal("3.00"))
                .build();
    }

    @Test
    @DisplayName("approves at income x multiple when there is no existing exposure")
    void approvesAtAffordabilityCap() {
        User user = TestFixtures.user(Money.of(40_000));
        UnderwritingDecision decision = engine.evaluate(new UnderwritingContext(user, lender(), Money.ZERO));

        assertThat(decision.approved()).isTrue();
        // min(40,000 x 2, 300,000 max, 120,000 exposure headroom) = 80,000
        assertThat(decision.sanctionedLimit()).isEqualByComparingTo(Money.of(80_000));
    }

    @Test
    @DisplayName("rejects when declared income is below the lender's floor")
    void rejectsBelowIncomeFloor() {
        User user = TestFixtures.user(Money.of(20_000));
        UnderwritingDecision decision = engine.evaluate(new UnderwritingContext(user, lender(), Money.ZERO));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.sanctionedLimit()).isEqualByComparingTo(Money.ZERO);
        assertThat(decision.failedRules()).extracting(RuleOutcome::ruleName).containsExactly("minimum-income");
    }

    @Test
    @DisplayName("existing exposure caps the sanction to the remaining headroom")
    void exposureHeadroomCapsTheSanction() {
        User user = TestFixtures.user(Money.of(40_000));
        // Ceiling 3 x 40,000 = 120,000; already holds 90,000 -> 30,000 headroom.
        UnderwritingDecision decision = engine.evaluate(
                new UnderwritingContext(user, lender(), Money.of(90_000)));

        assertThat(decision.approved()).isTrue();
        assertThat(decision.sanctionedLimit()).isEqualByComparingTo(Money.of(30_000));
    }

    @Test
    @DisplayName("rejects when the applicant is already at the exposure ceiling")
    void rejectsAtExposureCeiling() {
        User user = TestFixtures.user(Money.of(40_000));
        UnderwritingDecision decision = engine.evaluate(
                new UnderwritingContext(user, lender(), Money.of(120_000)));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.failedRules()).extracting(RuleOutcome::ruleName).containsExactly("existing-exposure");
    }

    @Test
    @DisplayName("the lender's hard maximum overrides a larger affordability cap")
    void lenderHardMaximumWins() {
        Lender smallLender = TestFixtures.lenderBuilder()
                .minMonthlyIncome(Money.of(10_000))
                .maxCreditLimit(Money.of(50_000))
                .incomeMultiple(new BigDecimal("5.00"))
                .maxExposureMultiple(new BigDecimal("10.00"))
                .build();
        User user = TestFixtures.user(Money.of(100_000));

        UnderwritingDecision decision = engine.evaluate(new UnderwritingContext(user, smallLender, Money.ZERO));

        assertThat(decision.sanctionedLimit()).isEqualByComparingTo(Money.of(50_000));
    }

    @Test
    @DisplayName("an inactive lender rejects everything, and every rule is still reported")
    void inactiveLenderRejects() {
        Lender inactive = TestFixtures.lenderBuilder().active(false).minMonthlyIncome(Money.of(1)).build();
        User user = TestFixtures.user(Money.of(500_000));

        UnderwritingDecision decision = engine.evaluate(new UnderwritingContext(user, inactive, Money.ZERO));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.outcomes()).hasSize(3);
        assertThat(decision.failedRules()).extracting(RuleOutcome::ruleName)
                .contains("lender-accepting-applications");
        assertThat(decision.summary()).contains("REJECTED");
    }

    @Test
    @DisplayName("every rule runs even after one fails, so all reasons are visible at once")
    void allRulesEvaluatedRegardlessOfEarlyFailure() {
        Lender inactive = TestFixtures.lenderBuilder()
                .active(false)
                .minMonthlyIncome(Money.of(999_999))
                .build();
        User user = TestFixtures.user(Money.of(1_000));

        UnderwritingDecision decision = engine.evaluate(new UnderwritingContext(user, inactive, Money.ZERO));

        assertThat(decision.failedRules()).extracting(RuleOutcome::ruleName)
                .contains("lender-accepting-applications", "minimum-income");
    }
}
