package com.fluxupi.transaction;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.transaction.state.TransactionState;
import com.fluxupi.transaction.state.TransactionStates;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single money movement against a credit line.
 *
 * <p><b>Idempotency.</b> {@code idempotencyKey} is client-supplied and carries a
 * {@code UNIQUE} constraint in the database — not merely an application-level
 * "check then insert", which two concurrent requests can both pass. The
 * {@code requestFingerprint} lets us tell an honest retry (same key, same
 * payload → return the original result) from a client bug (same key, different
 * amount → reject loudly).
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_line_id", nullable = false, updatable = false)
    private CreditLine creditLine;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransactionStatus status;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 128)
    private String idempotencyKey;

    /** Hash of the request payload, used to detect key reuse with different data. */
    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    /** Mock merchant VPA for a spend; null for repayments. */
    @Column(name = "payee_vpa", updatable = false)
    private String payeeVpa;

    @Column(name = "description", updatable = false)
    private String description;

    /** For a REVERSAL, the SPEND it unwinds. Null otherwise. */
    @Column(name = "reversal_of_id", updatable = false)
    private UUID reversalOfId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Transaction() {
        // for JPA
    }

    private Transaction(CreditLine creditLine, TransactionType type, BigDecimal amount,
                        String idempotencyKey, String requestFingerprint,
                        String payeeVpa, String description, UUID reversalOfId) {
        BigDecimal normalized = Money.normalize(amount);
        if (normalized == null || !Money.isPositive(normalized)) {
            throw new IllegalArgumentException("Transaction amount must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        this.id = UUID.randomUUID();
        this.creditLine = creditLine;
        this.type = type;
        this.status = TransactionStatus.INITIATED;
        this.amount = normalized;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.payeeVpa = payeeVpa;
        this.description = description;
        this.reversalOfId = reversalOfId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Transaction spend(CreditLine creditLine, BigDecimal amount, String idempotencyKey,
                                    String requestFingerprint, String payeeVpa, String description) {
        return new Transaction(creditLine, TransactionType.SPEND, amount, idempotencyKey,
                requestFingerprint, payeeVpa, description, null);
    }

    public static Transaction repayment(CreditLine creditLine, BigDecimal amount, String idempotencyKey,
                                        String requestFingerprint, String description) {
        return new Transaction(creditLine, TransactionType.REPAYMENT, amount, idempotencyKey,
                requestFingerprint, null, description, null);
    }

    public static Transaction reversal(Transaction original, String idempotencyKey,
                                       String requestFingerprint, String reason) {
        if (original.type != TransactionType.SPEND) {
            throw new IllegalArgumentException("Only a SPEND can be reversed, got " + original.type);
        }
        return new Transaction(original.creditLine, TransactionType.REVERSAL, original.amount,
                idempotencyKey, requestFingerprint, original.payeeVpa, reason, original.id);
    }

    // ---------------------------------------------------------------- lifecycle

    private void transitionTo(TransactionStatus target) {
        if (!currentState().canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Transaction " + id, status, target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public void markSuccess() {
        transitionTo(TransactionStatus.SUCCESS);
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        transitionTo(TransactionStatus.FAILED);
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    public void markReversed() {
        transitionTo(TransactionStatus.REVERSED);
    }

    // ------------------------------------------------------------------ getters

    public TransactionState currentState() {
        return TransactionStates.of(status);
    }

    public boolean isSuccessful() {
        return status == TransactionStatus.SUCCESS;
    }

    public UUID getId() {
        return id;
    }

    public CreditLine getCreditLine() {
        return creditLine;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getPayeeVpa() {
        return payeeVpa;
    }

    public String getDescription() {
        return description;
    }

    public UUID getReversalOfId() {
        return reversalOfId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Transaction transaction && id != null && id.equals(transaction.id);
    }

    @Override
    public int hashCode() {
        return Transaction.class.hashCode();
    }
}
