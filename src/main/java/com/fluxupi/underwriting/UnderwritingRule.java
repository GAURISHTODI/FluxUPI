package com.fluxupi.underwriting;

import org.springframework.core.Ordered;

/**
 * One inspectable underwriting check. Implementations are Spring beans picked up
 * automatically by {@link UnderwritingEngine}; {@link Ordered} controls the
 * order they appear in the decision's reason list.
 */
public interface UnderwritingRule extends Ordered {

    String name();

    RuleOutcome evaluate(UnderwritingContext context);

    @Override
    default int getOrder() {
        return 0;
    }
}
