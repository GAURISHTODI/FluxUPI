package com.fluxupi.repayment;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.repayment.state.InstallmentState;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * One scheduled monthly payment within a {@link RepaymentSchedule}.
 *
 * <p>Tracks how much of its principal and interest have actually been paid.
 * Partial payments are allowed: an instalment only reaches {@link
 * InstallmentStatus#PAID} once {@code paidAmount >= totalDue}, and the state
 * transition is guarded exactly like every other in the codebase.
 */
@Entity
@Table(name = "installments")
public class Installment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false, updatable = false)
    private RepaymentSchedule schedule;

    @Column(name = "installment_number", nullable = false, updatable = false)
    private int number;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "principal_component", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal principalComponent;

    @Column(name = "interest_component", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal interestComponent;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InstallmentStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected Installment() {
        // for JPA
    }

    Installment(RepaymentSchedule schedule, InstallmentSpec spec) {
        this.id = UUID.randomUUID();
        this.schedule = schedule;
        this.number = spec.number();
        this.dueDate = spec.dueDate();
        this.principalComponent = Money.normalize(spec.principalComponent());
        this.interestComponent = Money.normalize(spec.interestComponent());
        this.paidAmount = Money.ZERO;
        this.status = InstallmentStatus.UPCOMING;
    }

    // ---------------------------------------------------------------- lifecycle

    private void transitionTo(InstallmentStatus target) {
        InstallmentState current = InstallmentState.Registry.of(status);
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Installment " + id, status, target);
        }
        this.status = target;
    }

    /**
     * Recomputes DUE / OVERDUE from the clock. Called by a scheduled sweep and
     * before any payment is applied. Never moves a PAID instalment.
     */
    public void refreshStatusAsOf(LocalDate today) {
        if (status == InstallmentStatus.PAID) {
            return;
        }
        if (!today.isBefore(dueDate) && isFullyPaid()) {
            return;
        }
        if (today.isAfter(dueDate)) {
            if (status != InstallmentStatus.OVERDUE) {
                transitionTo(InstallmentStatus.OVERDUE);
            }
        } else if (!today.isBefore(dueDate)) {
            if (status == InstallmentStatus.UPCOMING) {
                transitionTo(InstallmentStatus.DUE);
            }
        }
    }

    /**
     * Applies {@code amount} to this instalment, interest first then principal,
     * and returns the split actually absorbed (the caller may have handed over
     * more than was outstanding).
     */
    RepaymentAllocation applyPayment(BigDecimal amount) {
        BigDecimal remaining = Money.normalize(amount);
        BigDecimal outstanding = outstandingAmount();
        BigDecimal absorbed = Money.isGreaterThan(remaining, outstanding) ? outstanding : remaining;

        BigDecimal interestOutstanding = Money.normalize(
                interestComponent.subtract(interestPaidSoFar()));
        BigDecimal interestPart = Money.isGreaterThan(absorbed, interestOutstanding)
                ? interestOutstanding : absorbed;
        BigDecimal principalPart = Money.normalize(absorbed.subtract(interestPart));

        this.paidAmount = Money.normalize(paidAmount.add(absorbed));

        if (isFullyPaid()) {
            transitionTo(InstallmentStatus.PAID);
            this.paidAt = Instant.now();
        }
        return new RepaymentAllocation(principalPart, interestPart);
    }

    // ------------------------------------------------------------------ amounts

    public BigDecimal totalDue() {
        return Money.normalize(principalComponent.add(interestComponent));
    }

    public BigDecimal outstandingAmount() {
        BigDecimal outstanding = Money.normalize(totalDue().subtract(paidAmount));
        return Money.isNegative(outstanding) ? Money.ZERO : outstanding;
    }

    public boolean isFullyPaid() {
        return Money.isAtLeast(paidAmount, totalDue());
    }

    /** Interest is settled before principal, so this is min(paid, interestComponent). */
    private BigDecimal interestPaidSoFar() {
        return Money.isGreaterThan(paidAmount, interestComponent) ? interestComponent : paidAmount;
    }

    // ------------------------------------------------------------------ getters

    public UUID getId() {
        return id;
    }

    public int getNumber() {
        return number;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getPrincipalComponent() {
        return principalComponent;
    }

    public BigDecimal getInterestComponent() {
        return interestComponent;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public InstallmentStatus getStatus() {
        return status;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    /** Principal / interest split absorbed by a single payment application. */
    public record RepaymentAllocation(BigDecimal principal, BigDecimal interest) {

        public BigDecimal total() {
            return Money.normalize(principal.add(interest));
        }
    }
}
