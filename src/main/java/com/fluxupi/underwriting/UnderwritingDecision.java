package com.fluxupi.underwriting;

import com.fluxupi.common.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * The outcome of running every rule.
 *
 * @param approved         true only if every rule passed and the sanctioned
 *                         limit came out positive
 * @param sanctionedLimit  the amount to approve — the smallest cap any rule
 *                         imposed, further capped at the lender's hard maximum;
 *                         zero when rejected
 * @param outcomes         every rule's result, in rule order, so the decision
 *                         is fully explainable after the fact
 */
public record UnderwritingDecision(boolean approved,
                                   BigDecimal sanctionedLimit,
                                   List<RuleOutcome> outcomes) {

    public UnderwritingDecision {
        sanctionedLimit = Money.normalize(sanctionedLimit);
        outcomes = List.copyOf(outcomes);
    }

    /** A one-line human summary joining every rule's reason. */
    public String summary() {
        String verdict = approved ? "APPROVED " + sanctionedLimit : "REJECTED";
        String detail = outcomes.stream()
                .map(o -> "%s[%s]: %s".formatted(o.ruleName(), o.passed() ? "pass" : "FAIL", o.reason()))
                .reduce((a, b) -> a + " | " + b)
                .orElse("no rules evaluated");
        return verdict + " — " + detail;
    }

    public List<RuleOutcome> failedRules() {
        return outcomes.stream().filter(o -> !o.passed()).toList();
    }
}
