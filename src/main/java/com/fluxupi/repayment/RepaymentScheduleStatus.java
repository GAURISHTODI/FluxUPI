package com.fluxupi.repayment;

/**
 * Status of a whole schedule, derived from its instalments.
 */
public enum RepaymentScheduleStatus {

    /** At least one instalment is still unpaid and none is overdue. */
    ACTIVE,

    /** At least one instalment is overdue. */
    DELINQUENT,

    /** Every instalment is paid. Terminal. */
    SETTLED,

    /** Superseded by a regenerated schedule after further borrowing. Terminal. */
    SUPERSEDED
}
