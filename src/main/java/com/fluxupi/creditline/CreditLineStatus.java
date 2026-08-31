package com.fluxupi.creditline;

/**
 * The persisted name of a credit line's state. This enum is a <em>label</em>
 * only — every rule about what a state permits lives in the matching
 * {@link com.fluxupi.creditline.state.CreditLineState} object, resolved via
 * {@link com.fluxupi.creditline.state.CreditLineStates}.
 */
public enum CreditLineStatus {

    /** Application submitted, underwriting not yet run. */
    PENDING,

    /** Underwriting passed and a limit was offered, but the line is not usable yet. */
    APPROVED,

    /** Underwriting failed. Terminal. */
    REJECTED,

    /** Live and spendable. */
    ACTIVE,

    /** Temporarily blocked (risk, missed payment). Reversible back to ACTIVE. */
    FROZEN,

    /** Settled and shut. Terminal. */
    CLOSED,

    /** Written off after prolonged non-payment. */
    DEFAULTED
}
