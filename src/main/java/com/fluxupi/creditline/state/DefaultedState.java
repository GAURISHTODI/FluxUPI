package com.fluxupi.creditline.state;

import com.fluxupi.creditline.CreditLineStatus;

import java.util.Set;

/**
 * Written off after prolonged non-payment. Not quite terminal: if the borrower
 * later settles, the line moves on to CLOSED. It can never go back to ACTIVE —
 * a defaulted line does not become spendable again.
 */
final class DefaultedState implements CreditLineState {

    @Override
    public CreditLineStatus status() {
        return CreditLineStatus.DEFAULTED;
    }

    @Override
    public Set<CreditLineStatus> allowedTransitions() {
        return Set.of(CreditLineStatus.CLOSED);
    }

    @Override
    public boolean allowsSpending() {
        return false;
    }
}
