package com.fluxupi.lender;

import com.fluxupi.common.Money;
import com.fluxupi.repayment.InterestStrategyType;
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
 * A simulated lending partner. Every number a lender uses to make decisions
 * lives here as a column rather than being hardcoded in a service, so the
 * underwriting outcome for any application can be explained by reading one row.
 *
 * <p>Real UPI credit lines are issued by an NBFC or bank sitting behind the
 * app; this entity is the stand-in for that party. No external call is made.
 */
@Entity
@Table(name = "lenders")
public class Lender {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Stable short code used in seeds and logs, e.g. {@code QUICKCASH}. */
    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** Applications from users earning less than this are rejected outright. */
    @Column(name = "min_monthly_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal minMonthlyIncome;

    /** Hard ceiling on any single credit line this lender will issue. */
    @Column(name = "max_credit_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal maxCreditLimit;

    /**
     * Offered limit is {@code monthlyIncome × incomeMultiple}, then capped at
     * {@link #maxCreditLimit}. A multiple of 2.00 on a ₹40,000 income offers
     * ₹80,000.
     */
    @Column(name = "income_multiple", nullable = false, precision = 6, scale = 2)
    private BigDecimal incomeMultiple;

    /**
     * Maximum total credit (across all lenders) a user may already hold,
     * expressed as a multiple of monthly income. At 3.00, someone earning
     * ₹40,000 who already has ₹120,000 of limits elsewhere is rejected.
     */
    @Column(name = "max_exposure_multiple", nullable = false, precision = 6, scale = 2)
    private BigDecimal maxExposureMultiple;

    @Column(name = "annual_interest_rate_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal annualInterestRatePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_strategy", nullable = false, length = 32)
    private InterestStrategyType interestStrategy;

    /** Number of monthly instalments a spend is broken into by default. */
    @Column(name = "default_tenure_months", nullable = false)
    private int defaultTenureMonths;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Lender() {
        // for JPA
    }

    private Lender(Builder builder) {
        this.id = UUID.randomUUID();
        this.code = builder.code;
        this.displayName = builder.displayName;
        this.minMonthlyIncome = Money.normalize(builder.minMonthlyIncome);
        this.maxCreditLimit = Money.normalize(builder.maxCreditLimit);
        this.incomeMultiple = builder.incomeMultiple;
        this.maxExposureMultiple = builder.maxExposureMultiple;
        this.annualInterestRatePercent = builder.annualInterestRatePercent;
        this.interestStrategy = builder.interestStrategy;
        this.defaultTenureMonths = builder.defaultTenureMonths;
        this.active = builder.active;
        this.createdAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public BigDecimal getMinMonthlyIncome() {
        return minMonthlyIncome;
    }

    public BigDecimal getMaxCreditLimit() {
        return maxCreditLimit;
    }

    public BigDecimal getIncomeMultiple() {
        return incomeMultiple;
    }

    public BigDecimal getMaxExposureMultiple() {
        return maxExposureMultiple;
    }

    public BigDecimal getAnnualInterestRatePercent() {
        return annualInterestRatePercent;
    }

    public InterestStrategyType getInterestStrategy() {
        return interestStrategy;
    }

    public int getDefaultTenureMonths() {
        return defaultTenureMonths;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Lender lender && id != null && id.equals(lender.id);
    }

    @Override
    public int hashCode() {
        return Lender.class.hashCode();
    }

    public static final class Builder {
        private String code;
        private String displayName;
        private BigDecimal minMonthlyIncome = Money.of(0);
        private BigDecimal maxCreditLimit = Money.of(0);
        private BigDecimal incomeMultiple = BigDecimal.ONE;
        private BigDecimal maxExposureMultiple = BigDecimal.TEN;
        private BigDecimal annualInterestRatePercent = BigDecimal.ZERO;
        private InterestStrategyType interestStrategy = InterestStrategyType.REDUCING_BALANCE;
        private int defaultTenureMonths = 3;
        private boolean active = true;

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder minMonthlyIncome(BigDecimal minMonthlyIncome) {
            this.minMonthlyIncome = minMonthlyIncome;
            return this;
        }

        public Builder maxCreditLimit(BigDecimal maxCreditLimit) {
            this.maxCreditLimit = maxCreditLimit;
            return this;
        }

        public Builder incomeMultiple(BigDecimal incomeMultiple) {
            this.incomeMultiple = incomeMultiple;
            return this;
        }

        public Builder maxExposureMultiple(BigDecimal maxExposureMultiple) {
            this.maxExposureMultiple = maxExposureMultiple;
            return this;
        }

        public Builder annualInterestRatePercent(BigDecimal annualInterestRatePercent) {
            this.annualInterestRatePercent = annualInterestRatePercent;
            return this;
        }

        public Builder interestStrategy(InterestStrategyType interestStrategy) {
            this.interestStrategy = interestStrategy;
            return this;
        }

        public Builder defaultTenureMonths(int defaultTenureMonths) {
            this.defaultTenureMonths = defaultTenureMonths;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public Lender build() {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Lender code is required");
            }
            if (defaultTenureMonths < 1) {
                throw new IllegalArgumentException("defaultTenureMonths must be at least 1");
            }
            return new Lender(this);
        }
    }
}
