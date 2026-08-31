package com.fluxupi.repayment;

import com.fluxupi.common.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Reducing-balance interest — a standard amortised EMI.
 *
 * <p>The equated monthly instalment is
 * <pre>
 *   EMI = P · r · (1 + r)^n / ((1 + r)^n − 1)
 * </pre>
 * where {@code r} is the monthly rate and {@code n} the tenure. Each month the
 * interest component is {@code outstanding × r} and the rest of the EMI reduces
 * the principal, so interest falls as the balance does.
 *
 * <p>The schedule is built by amortising month by month. The final instalment
 * is set to exactly whatever principal remains, which absorbs the rounding
 * drift from the fixed EMI and guarantees the balance lands on zero.
 *
 * <p>A zero interest rate is handled as a plain even split of principal.
 */
@Component
public class ReducingBalanceStrategy implements InterestStrategy {

    private static final MathContext MC = MathContext.DECIMAL64;

    @Override
    public InterestStrategyType type() {
        return InterestStrategyType.REDUCING_BALANCE;
    }

    @Override
    public RepaymentPlan generate(RepaymentTerms terms) {
        int n = terms.tenureMonths();
        BigDecimal principal = terms.principal();
        BigDecimal r = terms.monthlyRateFraction();

        if (Money.isZero(r)) {
            return interestFreePlan(terms);
        }

        BigDecimal onePlusRPowN = BigDecimal.ONE.add(r).pow(n, MC);
        BigDecimal emi = Money.normalize(principal
                .multiply(r, MC)
                .multiply(onePlusRPowN, MC)
                .divide(onePlusRPowN.subtract(BigDecimal.ONE), MC));

        List<InstallmentSpec> specs = new ArrayList<>(n);
        BigDecimal outstanding = principal;
        BigDecimal totalInterest = Money.ZERO;

        for (int i = 1; i <= n; i++) {
            BigDecimal interestPart = Money.normalize(outstanding.multiply(r, MC));
            BigDecimal principalPart;

            if (i == n) {
                // Final instalment clears whatever is left, exactly.
                principalPart = Money.normalize(outstanding);
            } else {
                principalPart = Money.normalize(emi.subtract(interestPart));
                if (Money.isGreaterThan(principalPart, outstanding)) {
                    principalPart = outstanding;
                }
            }

            outstanding = Money.normalize(outstanding.subtract(principalPart));
            totalInterest = totalInterest.add(interestPart);

            specs.add(new InstallmentSpec(i, terms.firstDueDate().plusMonths(i - 1L),
                    principalPart, interestPart));
        }

        return new RepaymentPlan(principal, Money.normalize(totalInterest), specs);
    }

    private RepaymentPlan interestFreePlan(RepaymentTerms terms) {
        int n = terms.tenureMonths();
        BigDecimal principal = terms.principal();
        BigDecimal perMonth = Money.normalize(
                principal.divide(BigDecimal.valueOf(n), 10, Money.ROUNDING));

        List<InstallmentSpec> specs = new ArrayList<>(n);
        BigDecimal allocated = Money.ZERO;
        for (int i = 1; i <= n; i++) {
            BigDecimal principalPart = i == n
                    ? Money.normalize(principal.subtract(allocated))
                    : perMonth;
            allocated = allocated.add(principalPart);
            specs.add(new InstallmentSpec(i, terms.firstDueDate().plusMonths(i - 1L),
                    principalPart, Money.ZERO));
        }
        return new RepaymentPlan(principal, Money.ZERO, specs);
    }
}
