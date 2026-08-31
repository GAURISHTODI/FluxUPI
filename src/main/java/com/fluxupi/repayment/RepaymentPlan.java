package com.fluxupi.repayment;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.LedgerImbalanceException;

import java.math.BigDecimal;
import java.util.List;

/**
 * A full computed repayment plan. Construction asserts the two identities that
 * every strategy must preserve, so a rounding bug in a strategy fails loudly
 * here rather than silently producing a schedule that collects the wrong total:
 *
 * <ul>
 *   <li>the principal components sum to exactly the amount borrowed;</li>
 *   <li>the totals sum to exactly principal + total interest.</li>
 * </ul>
 */
public record RepaymentPlan(BigDecimal principal,
                            BigDecimal totalInterest,
                            List<InstallmentSpec> installments) {

    public RepaymentPlan {
        principal = Money.normalize(principal);
        totalInterest = Money.normalize(totalInterest);
        installments = List.copyOf(installments);

        BigDecimal principalSum = installments.stream()
                .map(InstallmentSpec::principalComponent)
                .reduce(Money.ZERO, BigDecimal::add);
        if (!Money.isEqual(Money.normalize(principalSum), principal)) {
            throw new LedgerImbalanceException(
                    "Repayment plan principal components sum to %s, expected %s"
                            .formatted(Money.normalize(principalSum), principal));
        }

        BigDecimal totalSum = installments.stream()
                .map(InstallmentSpec::totalDue)
                .reduce(Money.ZERO, BigDecimal::add);
        BigDecimal expectedTotal = Money.normalize(principal.add(totalInterest));
        if (!Money.isEqual(Money.normalize(totalSum), expectedTotal)) {
            throw new LedgerImbalanceException(
                    "Repayment plan totals sum to %s, expected %s"
                            .formatted(Money.normalize(totalSum), expectedTotal));
        }
    }

    public BigDecimal totalPayable() {
        return Money.normalize(principal.add(totalInterest));
    }

    public int tenureMonths() {
        return installments.size();
    }
}
