package com.fluxupi.repayment;

import com.fluxupi.common.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Flat-rate interest: every instalment is charged interest on the <em>entire</em>
 * original principal, regardless of how much has already been repaid.
 *
 * <p>Total interest = {@code principal × annualRate × years}. The principal is
 * split evenly across instalments; the interest per instalment is the total
 * interest split evenly. Any rounding remainder is pushed into the final
 * instalment so the components reconcile exactly.
 *
 * <p>Worked example — ₹12,000 at 18%/yr flat over 12 months:
 * total interest = 12,000 × 0.18 × 1 = ₹2,160; each instalment = ₹1,000
 * principal + ₹180 interest = ₹1,180.
 */
@Component
public class FlatRateStrategy implements InterestStrategy {

    @Override
    public InterestStrategyType type() {
        return InterestStrategyType.FLAT_RATE;
    }

    @Override
    public RepaymentPlan generate(RepaymentTerms terms) {
        int n = terms.tenureMonths();
        BigDecimal principal = terms.principal();

        BigDecimal years = BigDecimal.valueOf(n)
                .divide(BigDecimal.valueOf(12), 10, Money.ROUNDING);
        BigDecimal totalInterest = Money.normalize(principal
                .multiply(terms.annualInterestRatePercent())
                .divide(BigDecimal.valueOf(100), 10, Money.ROUNDING)
                .multiply(years));

        BigDecimal principalPerMonth = Money.normalize(
                principal.divide(BigDecimal.valueOf(n), 10, Money.ROUNDING));
        BigDecimal interestPerMonth = Money.normalize(
                totalInterest.divide(BigDecimal.valueOf(n), 10, Money.ROUNDING));

        List<InstallmentSpec> specs = new ArrayList<>(n);
        BigDecimal principalAllocated = Money.ZERO;
        BigDecimal interestAllocated = Money.ZERO;

        for (int i = 1; i <= n; i++) {
            boolean last = i == n;
            BigDecimal principalPart = last
                    ? Money.normalize(principal.subtract(principalAllocated))
                    : principalPerMonth;
            BigDecimal interestPart = last
                    ? Money.normalize(totalInterest.subtract(interestAllocated))
                    : interestPerMonth;

            principalAllocated = principalAllocated.add(principalPart);
            interestAllocated = interestAllocated.add(interestPart);

            specs.add(new InstallmentSpec(i, terms.firstDueDate().plusMonths(i - 1L),
                    principalPart, interestPart));
        }

        return new RepaymentPlan(principal, totalInterest, specs);
    }
}
