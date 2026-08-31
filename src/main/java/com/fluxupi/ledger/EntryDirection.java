package com.fluxupi.ledger;

/**
 * Which side of the book an entry sits on. Amounts are always stored positive;
 * the direction carries the sign, which keeps {@code SUM(amount)} per side a
 * trivially checkable number.
 */
public enum EntryDirection {

    DEBIT,
    CREDIT;

    public EntryDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
