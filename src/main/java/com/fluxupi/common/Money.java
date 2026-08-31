package com.fluxupi.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money handling rules for the whole codebase, in one place.
 *
 * <p>Every rupee amount in FluxUPI is a {@link BigDecimal} scaled to 2 decimal
 * places with {@link RoundingMode#HALF_UP}. Doubles are never used for money —
 * {@code 0.1 + 0.2 != 0.3} in binary floating point, and a ledger that cannot
 * reconcile to the paisa is not a ledger.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = normalize(BigDecimal.ZERO);

    private Money() {
    }

    /** Rounds an amount to the canonical scale used by every column and comparison. */
    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(String amount) {
        return normalize(new BigDecimal(amount));
    }

    public static BigDecimal of(long amount) {
        return normalize(BigDecimal.valueOf(amount));
    }

    /**
     * Value-based comparison that ignores scale, so {@code 100.0} equals
     * {@code 100.00}. {@link BigDecimal#equals} does not, which is a classic
     * source of silent ledger mismatches.
     */
    public static boolean isEqual(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0;
    }

    public static boolean isPositive(BigDecimal amount) {
        return amount.signum() > 0;
    }

    public static boolean isNegative(BigDecimal amount) {
        return amount.signum() < 0;
    }

    public static boolean isZero(BigDecimal amount) {
        return amount.signum() == 0;
    }

    /** True when {@code a} is strictly greater than {@code b}. */
    public static boolean isGreaterThan(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    /** True when {@code a} is greater than or equal to {@code b}. */
    public static boolean isAtLeast(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0;
    }
}
