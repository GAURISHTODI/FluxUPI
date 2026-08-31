package com.fluxupi.ledger;

/**
 * The chart of accounts, kept deliberately small so the whole book can be held
 * in your head. These are the platform's own books — "we" below is FluxUPI
 * acting as the lending platform.
 *
 * <p>Normal balances follow standard accounting: assets and expenses increase
 * with a DEBIT, liabilities and income increase with a CREDIT.
 */
public enum LedgerAccount {

    /**
     * Asset. What borrowers owe us in principal. A spend debits (increases) it;
     * a repayment credits (decreases) it.
     */
    CUSTOMER_RECEIVABLE(AccountKind.ASSET),

    /**
     * Liability. Money the partner lender has funded to us and expects back.
     * Not touched by day-to-day spending; used when a line is drawn or settled
     * with the lender.
     */
    LENDER_PAYABLE(AccountKind.LIABILITY),

    /**
     * Liability. Amounts owed out to merchants for spends we have authorised.
     * Credited when a user spends, debited when a spend is reversed.
     */
    MERCHANT_PAYABLE(AccountKind.LIABILITY),

    /**
     * Asset. The platform's settlement float — cash actually received from
     * borrowers. Debited (increased) when a repayment lands.
     */
    SETTLEMENT_CASH(AccountKind.ASSET),

    /** Income. Interest earned, recognised when a repayment allocates to it. */
    INTEREST_INCOME(AccountKind.INCOME),

    /** Income. Late fees and penalties, recognised the same way as interest. */
    FEE_INCOME(AccountKind.INCOME);

    private final AccountKind kind;

    LedgerAccount(AccountKind kind) {
        this.kind = kind;
    }

    public AccountKind getKind() {
        return kind;
    }

    /** The direction that <em>increases</em> this account's balance. */
    public EntryDirection normalBalance() {
        return switch (kind) {
            case ASSET, EXPENSE -> EntryDirection.DEBIT;
            case LIABILITY, INCOME, EQUITY -> EntryDirection.CREDIT;
        };
    }

    public enum AccountKind {
        ASSET, LIABILITY, EQUITY, INCOME, EXPENSE
    }
}
