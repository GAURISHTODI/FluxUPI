package com.fluxupi.underwriting.rule;

import com.fluxupi.underwriting.RuleOutcome;
import com.fluxupi.underwriting.UnderwritingContext;
import com.fluxupi.underwriting.UnderwritingRule;
import org.springframework.stereotype.Component;

/** An inactive lender issues nothing. Checked first so nothing else has to. */
@Component
public class LenderAcceptingApplicationsRule implements UnderwritingRule {

    @Override
    public String name() {
        return "lender-accepting-applications";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public RuleOutcome evaluate(UnderwritingContext context) {
        if (!context.lender().isActive()) {
            return RuleOutcome.fail(name(),
                    "Lender %s is not currently accepting applications".formatted(context.lender().getCode()));
        }
        return RuleOutcome.pass(name(), "Lender is active");
    }
}
