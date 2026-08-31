package com.fluxupi.underwriting;

import com.fluxupi.common.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every {@link UnderwritingRule} and folds the results into one
 * {@link UnderwritingDecision}.
 *
 * <p>The logic is intentionally boring: <b>every</b> rule must pass, and the
 * sanctioned limit is the <b>minimum</b> of every cap any rule imposed, then
 * clamped to the lender's hard ceiling. All rules are evaluated even after one
 * fails, so the decision explains every reason at once rather than just the
 * first.
 */
@Component
public class UnderwritingEngine {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingEngine.class);

    private final List<UnderwritingRule> rules;

    public UnderwritingEngine(List<UnderwritingRule> rules) {
        List<UnderwritingRule> ordered = new ArrayList<>(rules);
        AnnotationAwareOrderComparator.sort(ordered);
        this.rules = List.copyOf(ordered);
        log.info("Underwriting engine initialised with rules: {}",
                this.rules.stream().map(UnderwritingRule::name).toList());
    }

    public UnderwritingDecision evaluate(UnderwritingContext context) {
        List<RuleOutcome> outcomes = new ArrayList<>(rules.size());
        boolean allPassed = true;
        BigDecimal limit = context.lender().getMaxCreditLimit();

        for (UnderwritingRule rule : rules) {
            RuleOutcome outcome = rule.evaluate(context);
            outcomes.add(outcome);
            if (!outcome.passed()) {
                allPassed = false;
            }
            if (outcome.hasCap() && Money.isPositive(outcome.cap()) && outcome.cap().compareTo(limit) < 0) {
                limit = Money.normalize(outcome.cap());
            }
        }

        BigDecimal sanctioned = allPassed && Money.isPositive(limit) ? Money.normalize(limit) : Money.ZERO;
        boolean approved = allPassed && Money.isPositive(sanctioned);

        UnderwritingDecision decision = new UnderwritingDecision(approved, sanctioned, outcomes);
        log.debug("Underwriting for user {} / lender {}: {}",
                context.user().getId(), context.lender().getCode(), decision.summary());
        return decision;
    }
}
