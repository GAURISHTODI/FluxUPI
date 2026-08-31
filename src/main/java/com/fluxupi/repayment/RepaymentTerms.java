package com.fluxupi.repayment;

import com.fluxupi.common.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The inputs an {@link InterestStrategy} needs to lay out a repayment plan:
 * how much was borrowed, at what annual rate, over how many monthly
 * instalments, and when the first one falls due.
 */
public record RepaymentTerms(BigDecimal principal,
                             BigDecimal annualInterestRatePercent,
                             int tenureMonths,
                             LocalDate firstDueDate) {

    public RepaymentTerms {
        principal = Money.normalize(Objects.requireNonNull(principal, "principal"));
        Objects.requireNonNull(annualInterestRatePercent, "annualInterestRatePercent");
        Objects.requireNonNull(firstDueDate, "firstDueDate");
        if (!Money.isPositive(principal)) {
            throw new IllegalArgumentException("principal must be positive");
        }
        if (annualInterestRatePercent.signum() < 0) {
            throw new IllegalArgumentException("annualInterestRatePercent cannot be negative");
        }
        if (tenureMonths < 1) {
            throw new IllegalArgumentException("tenureMonths must be at least 1");
        }
    }

    /** Monthly rate as a fraction, e.g. 18%/yr -> 0.015. */
    public BigDecimal monthlyRateFraction() {
        return annualInterestRatePercent
                .divide(BigDecimal.valueOf(100), 10, Money.ROUNDING)
                .divide(BigDecimal.valueOf(12), 10, Money.ROUNDING);
    }
}
