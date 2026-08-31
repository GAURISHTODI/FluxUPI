package com.fluxupi.underwriting.rule;

import com.fluxupi.common.Money;
import com.fluxupi.underwriting.RuleOutcome;
import com.fluxupi.underwriting.UnderwritingContext;
import com.fluxupi.underwriting.UnderwritingRule;
import org.springframework.stereotype.Component;

/**
 * Rejects applicants whose declared monthly income is below the lender's floor.
 * Also caps the sanction at {@code income × incomeMultiple}, which is the
 * lender's affordability view of how much this applicant can carry.
 */
@Component
public class MinimumIncomeRule implements UnderwritingRule {

    @Override
    public String name() {
        return "minimum-income";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public RuleOutcome evaluate(UnderwritingContext context) {
        var income = context.monthlyIncome();
        var floor = context.lender().getMinMonthlyIncome();

        if (!Money.isAtLeast(income, floor)) {
            return RuleOutcome.fail(name(),
                    "Declared income %s is below the lender minimum of %s".formatted(income, floor));
        }

        var affordabilityCap = Money.normalize(income.multiply(context.lender().getIncomeMultiple()));
        return RuleOutcome.passWithCap(name(),
                "Income %s clears the %s minimum; affordability cap %s".formatted(income, floor, affordabilityCap),
                affordabilityCap);
    }
}
