package com.fluxupi.repayment;

/**
 * Which interest formula a lender bills with. Stored on the {@code Lender} row
 * and resolved to an {@link InterestStrategy} bean at runtime, so a lender's
 * pricing model is data, not a code branch.
 */
public enum InterestStrategyType {

    /** Interest charged on the full original principal for every instalment. */
    FLAT_RATE,

    /** Interest charged only on the outstanding principal — a standard EMI. */
    REDUCING_BALANCE
}
