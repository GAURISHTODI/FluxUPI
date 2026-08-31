package com.fluxupi.repayment;

/**
 * Lifecycle label for a single instalment. Transition rules live in
 * {@link com.fluxupi.repayment.state.InstallmentState} objects.
 */
public enum InstallmentStatus {

    /** Due date is in the future. */
    UPCOMING,

    /** Due date has arrived and it is not yet fully paid. */
    DUE,

    /** Past the due date and still unpaid. */
    OVERDUE,

    /** Settled in full. Terminal. */
    PAID
}
