package com.fluxupi.creditline;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.common.exception.InsufficientCreditLimitException;
import com.fluxupi.creditline.state.CreditLineState;
import com.fluxupi.creditline.state.CreditLineStates;
import com.fluxupi.lender.Lender;
import com.fluxupi.repayment.InterestStrategyType;
import com.fluxupi.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A revolving credit line issued to a {@link User} by a {@link Lender}.
 *
 * <p>Two invariants are enforced here rather than in any service:
 * <ol>
 *   <li>{@code status} is never assigned directly. Every change goes through
 *       {@link #transitionTo}, which asks the current
 *       {@link CreditLineState} whether the move is legal.</li>
 *   <li>{@code availableLimit} always stays within {@code [0, approvedLimit]}.
 *       Spending past zero throws; repaying past the approved limit is clamped.</li>
 * </ol>
 *
 * <p>Interest terms are <em>snapshotted</em> from the lender at approval time.
 * If the lender later reprices, existing lines keep the terms they were sold
 * under — repricing live debt retroactively is exactly the bug this avoids.
 */
@Entity
@Table(name = "credit_lines")
public class CreditLine {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lender_id", nullable = false, updatable = false)
    private Lender lender;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CreditLineStatus status;

    /** Total sanctioned limit. Zero until underwriting approves. */
    @Column(name = "approved_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal approvedLimit;

    /** Headroom left to spend. Drops on a spend, recovers on a repayment or reversal. */
    @Column(name = "available_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal availableLimit;

    @Column(name = "annual_interest_rate_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal annualInterestRatePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_strategy", nullable = false, length = 32)
    private InterestStrategyType interestStrategy;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock guard. The spend path additionally takes a pessimistic
     * row lock (see {@code CreditLineRepository#findByIdForUpdate}), so this is
     * the backstop for every other writer, not the primary concurrency control.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CreditLine() {
        // for JPA
    }

    private CreditLine(User user, Lender lender) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.lender = lender;
        this.status = CreditLineStatus.PENDING;
        this.approvedLimit = Money.ZERO;
        this.availableLimit = Money.ZERO;
        this.annualInterestRatePercent = lender.getAnnualInterestRatePercent();
        this.interestStrategy = lender.getInterestStrategy();
        this.tenureMonths = lender.getDefaultTenureMonths();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Creates a fresh application in PENDING. Underwriting has not run yet. */
    public static CreditLine apply(User user, Lender lender) {
        return new CreditLine(user, lender);
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * The one door every status change walks through. Anything that bypasses
     * this — a setter, a repository update, a raw SQL patch — is a bug.
     */
    private void transitionTo(CreditLineStatus target) {
        CreditLineState current = currentState();
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("CreditLine " + id, status, target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public void approve(BigDecimal sanctionedLimit, String reason) {
        BigDecimal limit = Money.normalize(sanctionedLimit);
        if (!Money.isPositive(limit)) {
            throw new IllegalArgumentException("Approved limit must be positive");
        }
        transitionTo(CreditLineStatus.APPROVED);
        this.approvedLimit = limit;
        this.availableLimit = limit;
        this.decisionReason = reason;
        this.approvedAt = Instant.now();
    }

    public void reject(String reason) {
        transitionTo(CreditLineStatus.REJECTED);
        this.decisionReason = reason;
    }

    public void activate() {
        transitionTo(CreditLineStatus.ACTIVE);
        this.activatedAt = Instant.now();
    }

    public void freeze(String reason) {
        transitionTo(CreditLineStatus.FROZEN);
        this.decisionReason = reason;
    }

    public void unfreeze() {
        transitionTo(CreditLineStatus.ACTIVE);
        this.decisionReason = null;
    }

    public void close(String reason) {
        transitionTo(CreditLineStatus.CLOSED);
        this.decisionReason = reason;
        this.closedAt = Instant.now();
    }

    public void markDefaulted(String reason) {
        transitionTo(CreditLineStatus.DEFAULTED);
        this.decisionReason = reason;
    }

    // ------------------------------------------------------------------- limits

    /**
     * Reserves {@code amount} of headroom for a spend.
     *
     * <p>Callers must already hold a row lock on this credit line — the
     * check-then-decrement below is only atomic if two spends cannot interleave
     * between the two lines. {@code TransactionService} takes that lock.
     */
    public void authorizeSpend(BigDecimal amount) {
        BigDecimal spend = requirePositive(amount, "Spend amount");
        if (!currentState().allowsSpending()) {
            throw new IllegalStateTransitionException(
                    "CreditLine " + id + " cannot authorise a spend", status, CreditLineStatus.ACTIVE);
        }
        if (!Money.isAtLeast(availableLimit, spend)) {
            throw new InsufficientCreditLimitException(id, spend, availableLimit);
        }
        this.availableLimit = Money.normalize(availableLimit.subtract(spend));
        this.updatedAt = Instant.now();
    }

    /**
     * Gives headroom back — used by both repayments and reversals. Clamped at
     * {@code approvedLimit} so a double-credit can never inflate the line
     * beyond what was sanctioned.
     */
    public void restoreLimit(BigDecimal amount) {
        BigDecimal credit = requirePositive(amount, "Restore amount");
        BigDecimal restored = Money.normalize(availableLimit.add(credit));
        this.availableLimit = Money.isGreaterThan(restored, approvedLimit) ? approvedLimit : restored;
        this.updatedAt = Instant.now();
    }

    /** Principal currently drawn down: {@code approvedLimit - availableLimit}. */
    public BigDecimal getUtilizedLimit() {
        return Money.normalize(approvedLimit.subtract(availableLimit));
    }

    private static BigDecimal requirePositive(BigDecimal amount, String label) {
        BigDecimal normalized = Money.normalize(amount);
        if (normalized == null || !Money.isPositive(normalized)) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return normalized;
    }

    // ------------------------------------------------------------------ getters

    public CreditLineState currentState() {
        return CreditLineStates.of(status);
    }

    public boolean canTransitionTo(CreditLineStatus target) {
        return currentState().canTransitionTo(target);
    }

    public boolean isSpendable() {
        return currentState().allowsSpending();
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Lender getLender() {
        return lender;
    }

    public CreditLineStatus getStatus() {
        return status;
    }

    public BigDecimal getApprovedLimit() {
        return approvedLimit;
    }

    public BigDecimal getAvailableLimit() {
        return availableLimit;
    }

    public BigDecimal getAnnualInterestRatePercent() {
        return annualInterestRatePercent;
    }

    public InterestStrategyType getInterestStrategy() {
        return interestStrategy;
    }

    public int getTenureMonths() {
        return tenureMonths;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof CreditLine line && id != null && id.equals(line.id);
    }

    @Override
    public int hashCode() {
        return CreditLine.class.hashCode();
    }
}
