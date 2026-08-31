package com.fluxupi.transaction;

/**
 * The persisted name of a transaction's state. As with credit lines, the rules
 * live in {@link com.fluxupi.transaction.state.TransactionState} objects, not here.
 */
public enum TransactionStatus {

    /** Accepted and being processed. Nothing has been committed to the ledger yet. */
    INITIATED,

    /** Committed: limits moved and balanced ledger entries were written. */
    SUCCESS,

    /** Rejected before any ledger entry was written. Terminal. */
    FAILED,

    /** A successful transaction that was later unwound by a REVERSAL. Terminal. */
    REVERSED
}
