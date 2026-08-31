package com.fluxupi.repayment;

import com.fluxupi.common.Money;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of a computed repayment plan, before it is persisted as an
 * {@link Installment}. The total due is always {@code principalComponent +
 * interestComponent} to the paisa.
 */
public record InstallmentSpec(int number,
                              LocalDate dueDate,
                              BigDecimal principalComponent,
                              BigDecimal interestComponent) {

    public InstallmentSpec {
        principalComponent = Money.normalize(principalComponent);
        interestComponent = Money.normalize(interestComponent);
        if (number < 1) {
            throw new IllegalArgumentException("Instalment number starts at 1");
        }
        if (Money.isNegative(principalComponent) || Money.isNegative(interestComponent)) {
            throw new IllegalArgumentException("Instalment components cannot be negative");
        }
    }

    public BigDecimal totalDue() {
        return Money.normalize(principalComponent.add(interestComponent));
    }
}
