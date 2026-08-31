package com.fluxupi.repayment;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.creditline.CreditLine;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The EMI plan for the principal outstanding on a {@link CreditLine} at the
 * moment it is generated.
 *
 * <p>A schedule is a snapshot. Borrowing more after one is generated does not
 * mutate it — instead {@code RepaymentService} marks it {@code SUPERSEDED} and
 * generates a fresh one covering the new total. This keeps every schedule a
 * faithful record of what the borrower was told at a point in time.
 */
@Entity
@Table(name = "repayment_schedules")
public class RepaymentSchedule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_line_id", nullable = false, updatable = false)
    private CreditLine creditLine;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RepaymentScheduleStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_strategy", nullable = false, length = 32, updatable = false)
    private InterestStrategyType interestStrategy;

    @Column(name = "principal", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal principal;

    @Column(name = "total_interest", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal totalInterest;

    @Column(name = "annual_interest_rate_percent", nullable = false, precision = 6, scale = 3, updatable = false)
    private BigDecimal annualInterestRatePercent;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("number ASC")
    private List<Installment> installments = new ArrayList<>();

    protected RepaymentSchedule() {
        // for JPA
    }

    private RepaymentSchedule(CreditLine creditLine, InterestStrategyType strategy, RepaymentPlan plan) {
        this.id = UUID.randomUUID();
        this.creditLine = creditLine;
        this.status = RepaymentScheduleStatus.ACTIVE;
        this.interestStrategy = strategy;
        this.principal = Money.normalize(plan.principal());
        this.totalInterest = Money.normalize(plan.totalInterest());
        this.annualInterestRatePercent = creditLine.getAnnualInterestRatePercent();
        this.generatedAt = Instant.now();
        for (InstallmentSpec spec : plan.installments()) {
            this.installments.add(new Installment(this, spec));
        }
    }

    public static RepaymentSchedule from(CreditLine creditLine, InterestStrategyType strategy, RepaymentPlan plan) {
        return new RepaymentSchedule(creditLine, strategy, plan);
    }

    // ---------------------------------------------------------------- lifecycle

    private void transitionTo(RepaymentScheduleStatus target) {
        boolean allowed = switch (status) {
            case ACTIVE -> target != RepaymentScheduleStatus.ACTIVE;
            case DELINQUENT -> target == RepaymentScheduleStatus.ACTIVE
                    || target == RepaymentScheduleStatus.SETTLED
                    || target == RepaymentScheduleStatus.SUPERSEDED;
            case SETTLED, SUPERSEDED -> false;
        };
        if (!allowed) {
            throw new IllegalStateTransitionException("RepaymentSchedule " + id, status, target);
        }
        this.status = target;
    }

    /**
     * Rolls instalment and schedule status forward to {@code today}: instalments
     * become DUE / OVERDUE as their dates pass, and the schedule becomes
     * DELINQUENT if any instalment is overdue, SETTLED once all are paid.
     */
    public void refreshAsOf(LocalDate today) {
        if (status == RepaymentScheduleStatus.SUPERSEDED) {
            return;
        }
        installments.forEach(installment -> installment.refreshStatusAsOf(today));

        if (installments.stream().allMatch(Installment::isFullyPaid)) {
            if (status != RepaymentScheduleStatus.SETTLED) {
                transitionTo(RepaymentScheduleStatus.SETTLED);
            }
            return;
        }
        boolean anyOverdue = installments.stream()
                .anyMatch(i -> i.getStatus() == InstallmentStatus.OVERDUE);
        if (anyOverdue && status == RepaymentScheduleStatus.ACTIVE) {
            transitionTo(RepaymentScheduleStatus.DELINQUENT);
        } else if (!anyOverdue && status == RepaymentScheduleStatus.DELINQUENT) {
            transitionTo(RepaymentScheduleStatus.ACTIVE);
        }
    }

    public void supersede() {
        transitionTo(RepaymentScheduleStatus.SUPERSEDED);
    }

    void markSettledIfComplete() {
        if (installments.stream().allMatch(Installment::isFullyPaid)
                && status != RepaymentScheduleStatus.SETTLED) {
            transitionTo(RepaymentScheduleStatus.SETTLED);
        }
    }

    /** The earliest instalment that still has something outstanding. */
    public Optional<Installment> nextPayable() {
        return installments.stream()
                .filter(i -> !i.isFullyPaid())
                .min(Comparator.comparingInt(Installment::getNumber));
    }

    // ------------------------------------------------------------------ amounts

    public BigDecimal totalPayable() {
        return Money.normalize(principal.add(totalInterest));
    }

    public BigDecimal totalPaid() {
        return Money.normalize(installments.stream()
                .map(Installment::getPaidAmount)
                .reduce(Money.ZERO, BigDecimal::add));
    }

    public BigDecimal outstandingAmount() {
        return Money.normalize(totalPayable().subtract(totalPaid()));
    }

    // ------------------------------------------------------------------ getters

    public UUID getId() {
        return id;
    }

    public CreditLine getCreditLine() {
        return creditLine;
    }

    public RepaymentScheduleStatus getStatus() {
        return status;
    }

    public InterestStrategyType getInterestStrategy() {
        return interestStrategy;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public BigDecimal getTotalInterest() {
        return totalInterest;
    }

    public BigDecimal getAnnualInterestRatePercent() {
        return annualInterestRatePercent;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public List<Installment> getInstallments() {
        return List.copyOf(installments);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof RepaymentSchedule schedule && id != null && id.equals(schedule.id);
    }

    @Override
    public int hashCode() {
        return RepaymentSchedule.class.hashCode();
    }
}
