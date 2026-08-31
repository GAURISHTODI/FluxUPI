package com.fluxupi.ledger;

import com.fluxupi.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable line in the double-entry book.
 *
 * <p>Entries are append-only: there is no setter, no update path, and no delete
 * path anywhere in the codebase. Correcting a posting means writing an opposing
 * one, exactly as a paper ledger works. That is what makes the reconciliation
 * claim meaningful — history cannot be quietly rewritten to make the sums work.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Plain UUID rather than a {@code @ManyToOne}: entries are written in bulk
     * and only ever read back by transaction id, so an association would buy
     * nothing and cost a join.
     */
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "credit_line_id", nullable = false, updatable = false)
    private UUID creditLineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account", nullable = false, length = 48, updatable = false)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8, updatable = false)
    private EntryDirection direction;

    /** Always positive. The sign lives in {@link #direction}. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    /** Position of this line within its journal entry, starting at 0. */
    @Column(name = "entry_seq", nullable = false, updatable = false)
    private int entrySeq;

    @Column(name = "narrative", updatable = false)
    private String narrative;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    LedgerEntry(UUID transactionId, UUID creditLineId, LedgerPosting posting, int entrySeq) {
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.creditLineId = creditLineId;
        this.account = posting.account();
        this.direction = posting.direction();
        this.amount = Money.normalize(posting.amount());
        this.entrySeq = entrySeq;
        this.narrative = posting.narrative();
        this.createdAt = Instant.now();
    }

    /**
     * The amount as it affects a running total: positive for a debit, negative
     * for a credit. Summing this across a balanced book yields exactly zero.
     */
    public BigDecimal signedAmount() {
        return direction == EntryDirection.DEBIT ? amount : amount.negate();
    }

    public boolean isDebit() {
        return direction == EntryDirection.DEBIT;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getCreditLineId() {
        return creditLineId;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getEntrySeq() {
        return entrySeq;
    }

    public String getNarrative() {
        return narrative;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof LedgerEntry entry && id != null && id.equals(entry.id);
    }

    @Override
    public int hashCode() {
        return LedgerEntry.class.hashCode();
    }
}
