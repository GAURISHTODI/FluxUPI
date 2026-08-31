package com.fluxupi.user;

import com.fluxupi.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A simulated borrower.
 *
 * <p>{@code vpa} is a mock UPI handle (e.g. {@code asha@fluxbank}); no VPA
 * resolution ever leaves this codebase. {@code declaredMonthlyIncome} is
 * self-reported and is the main input to underwriting — there is no real KYC
 * or bureau lookup anywhere in this project.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** Mock UPI handle. Unique across users, mirroring how a real VPA behaves. */
    @Column(name = "vpa", nullable = false, unique = true)
    private String vpa;

    @Column(name = "declared_monthly_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal declaredMonthlyIncome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // for JPA
    }

    private User(UUID id, String fullName, String vpa, BigDecimal declaredMonthlyIncome, Instant createdAt) {
        this.id = id;
        this.fullName = fullName;
        this.vpa = vpa;
        this.declaredMonthlyIncome = declaredMonthlyIncome;
        this.createdAt = createdAt;
    }

    public static User register(String fullName, String vpa, BigDecimal declaredMonthlyIncome) {
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(vpa, "vpa");
        BigDecimal income = Money.normalize(Objects.requireNonNull(declaredMonthlyIncome, "declaredMonthlyIncome"));
        if (Money.isNegative(income)) {
            throw new IllegalArgumentException("declaredMonthlyIncome cannot be negative");
        }
        return new User(UUID.randomUUID(), fullName.trim(), vpa.trim().toLowerCase(), income, Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getVpa() {
        return vpa;
    }

    public BigDecimal getDeclaredMonthlyIncome() {
        return declaredMonthlyIncome;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof User user && id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return User.class.hashCode();
    }
}
