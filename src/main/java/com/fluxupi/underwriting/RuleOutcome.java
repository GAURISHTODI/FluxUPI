package com.fluxupi.underwriting;

/**
 * The result of one rule. {@code cap} is the largest limit this rule will allow
 * to be sanctioned; a passing rule with no opinion on amount returns
 * {@link #NO_CAP}.
 */
public record RuleOutcome(String ruleName, boolean passed, String reason, java.math.BigDecimal cap) {

    public static final java.math.BigDecimal NO_CAP = null;

    public static RuleOutcome pass(String ruleName, String reason) {
        return new RuleOutcome(ruleName, true, reason, NO_CAP);
    }

    public static RuleOutcome passWithCap(String ruleName, String reason, java.math.BigDecimal cap) {
        return new RuleOutcome(ruleName, true, reason, cap);
    }

    public static RuleOutcome fail(String ruleName, String reason) {
        return new RuleOutcome(ruleName, false, reason, NO_CAP);
    }

    public boolean hasCap() {
        return cap != null;
    }
}
