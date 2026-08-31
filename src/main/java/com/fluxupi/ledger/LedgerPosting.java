package com.fluxupi.ledger;

import com.fluxupi.common.Money;

import java.math.BigDecimal;

/**
 * One proposed line of a journal entry, before it is persisted. A caller hands
 * {@link LedgerService} a list of these; the service checks they balance and
 * only then writes {@link LedgerEntry} rows.
 */
public record LedgerPosting(LedgerAccount account, EntryDirection direction, BigDecimal amount, String narrative) {

    public LedgerPosting {
        amount = Money.normalize(amount);
        if (amount == null || !Money.isPositive(amount)) {
            throw new IllegalArgumentException("Ledger posting amount must be positive, got " + amount);
        }
    }

    public static LedgerPosting debit(LedgerAccount account, BigDecimal amount, String narrative) {
        return new LedgerPosting(account, EntryDirection.DEBIT, amount, narrative);
    }

    public static LedgerPosting credit(LedgerAccount account, BigDecimal amount, String narrative) {
        return new LedgerPosting(account, EntryDirection.CREDIT, amount, narrative);
    }

    public boolean isDebit() {
        return direction == EntryDirection.DEBIT;
    }
}
