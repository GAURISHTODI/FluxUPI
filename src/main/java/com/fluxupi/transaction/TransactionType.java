package com.fluxupi.transaction;

/** What kind of money movement a {@link Transaction} represents. */
public enum TransactionType {

    /** A drawdown against the credit line — the user paying a merchant. */
    SPEND,

    /** Money coming back from the user, settling principal and interest. */
    REPAYMENT,

    /** Unwinds an earlier successful SPEND. Always points at the original. */
    REVERSAL
}
