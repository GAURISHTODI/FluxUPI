package com.fluxupi.underwriting;

import com.fluxupi.common.Money;
import com.fluxupi.lender.Lender;
import com.fluxupi.user.User;

import java.math.BigDecimal;

/**
 * Everything a {@link UnderwritingRule} is allowed to look at.
 *
 * <p>Deliberately just the applicant, the lender's published parameters, and
 * the applicant's already-sanctioned credit elsewhere. There is no bureau
 * score, no bank-statement analysis, no device data — the point of this module
 * is a rule set an engineer can read top to bottom and explain, not a model.
 *
 * @param existingExposure total approved limit the user already holds across
 *                         all active/approved credit lines
 */
public record UnderwritingContext(User user, Lender lender, BigDecimal existingExposure) {

    public UnderwritingContext {
        existingExposure = Money.normalize(existingExposure == null ? Money.ZERO : existingExposure);
    }

    public BigDecimal monthlyIncome() {
        return user.getDeclaredMonthlyIncome();
    }
}
