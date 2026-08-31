package com.fluxupi.underwriting.rule;

import com.fluxupi.common.Money;
import com.fluxupi.underwriting.RuleOutcome;
import com.fluxupi.underwriting.UnderwritingContext;
import com.fluxupi.underwriting.UnderwritingRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Limits total borrowing across all lenders to {@code income ×
 * maxExposureMultiple}.
 *
 * <p>If the applicant is already at or above that ceiling, the application is
 * rejected. Otherwise the remaining headroom becomes a cap on this sanction, so
 * a new line can never push the applicant past the ceiling.
 *
 * <p>Example — income ₹40,000, multiple 3.0 → ceiling ₹120,000. Already holds
 * ₹90,000 elsewhere → this line is capped at ₹30,000.
 */
@Component
public class ExistingExposureRule implements UnderwritingRule {

    @Override
    public String name() {
        return "existing-exposure";
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public RuleOutcome evaluate(UnderwritingContext context) {
        BigDecimal ceiling = Money.normalize(
                context.monthlyIncome().multiply(context.lender().getMaxExposureMultiple()));
        BigDecimal current = context.existingExposure();
        BigDecimal headroom = Money.normalize(ceiling.subtract(current));

        if (!Money.isPositive(headroom)) {
            return RuleOutcome.fail(name(),
                    "Existing exposure %s already meets the ceiling of %s (%.2f× income)"
                            .formatted(current, ceiling, context.lender().getMaxExposureMultiple()));
        }
        return RuleOutcome.passWithCap(name(),
                "Existing exposure %s leaves %s of headroom under the %s ceiling"
                        .formatted(current, headroom, ceiling),
                headroom);
    }
}
