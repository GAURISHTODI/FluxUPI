package com.fluxupi;

import com.fluxupi.common.Money;
import com.fluxupi.creditline.CreditLine;
import com.fluxupi.creditline.CreditLineStatus;
import com.fluxupi.lender.Lender;
import com.fluxupi.repayment.InterestStrategyType;
import com.fluxupi.user.User;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared object builders for tests. Keeping them here means a test that cares
 * about one rule does not have to spell out six unrelated fields to get a valid
 * object, and every test starts from the same known-good baseline.
 */
public final class TestFixtures {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private TestFixtures() {
    }

    public static User user() {
        return user(Money.of(50_000));
    }

    public static User user(BigDecimal monthlyIncome) {
        long n = SEQUENCE.incrementAndGet();
        return User.register("Test User " + n, "test.user." + n + "@fluxbank", monthlyIncome);
    }

    public static Lender lender() {
        return lenderBuilder().build();
    }

    public static Lender.Builder lenderBuilder() {
        long n = SEQUENCE.incrementAndGet();
        return Lender.builder()
                .code("TESTLENDER" + n)
                .displayName("Test Lender " + n)
                .minMonthlyIncome(Money.of(25_000))
                .maxCreditLimit(Money.of(200_000))
                .incomeMultiple(new BigDecimal("2.00"))
                .maxExposureMultiple(new BigDecimal("3.00"))
                .annualInterestRatePercent(new BigDecimal("18.000"))
                .interestStrategy(InterestStrategyType.REDUCING_BALANCE)
                .defaultTenureMonths(3)
                .active(true);
    }

    /** A brand new application, still PENDING. */
    public static CreditLine pendingCreditLine() {
        return CreditLine.apply(user(), lender());
    }

    /** An ACTIVE line with the given sanctioned limit, fully available. */
    public static CreditLine activeCreditLine(BigDecimal limit) {
        CreditLine line = pendingCreditLine();
        line.approve(limit, "fixture approval");
        line.activate();
        return line;
    }

    public static CreditLine activeCreditLine() {
        return activeCreditLine(Money.of(100_000));
    }

    /**
     * Walks a credit line to {@code target} using only legal transitions, so
     * tests can set up any state without reaching past the state machine.
     */
    public static CreditLine creditLineIn(CreditLineStatus target) {
        CreditLine line = pendingCreditLine();
        BigDecimal limit = Money.of(100_000);
        return switch (target) {
            case PENDING -> line;
            case REJECTED -> {
                line.reject("fixture rejection");
                yield line;
            }
            case APPROVED -> {
                line.approve(limit, "fixture approval");
                yield line;
            }
            case ACTIVE -> {
                line.approve(limit, "fixture approval");
                line.activate();
                yield line;
            }
            case FROZEN -> {
                line.approve(limit, "fixture approval");
                line.activate();
                line.freeze("fixture freeze");
                yield line;
            }
            case CLOSED -> {
                line.approve(limit, "fixture approval");
                line.activate();
                line.close("fixture close");
                yield line;
            }
            case DEFAULTED -> {
                line.approve(limit, "fixture approval");
                line.activate();
                line.markDefaulted("fixture default");
                yield line;
            }
        };
    }
}
