package com.fluxupi.creditline.state;

import com.fluxupi.creditline.CreditLineStatus;

import java.util.Set;

/**
 * Spending is blocked but the debt is still live and repayable. This is the one
 * reversible non-terminal state: a frozen line can go back to ACTIVE once the
 * reason for the freeze clears.
 */
final class FrozenState implements CreditLineState {

    @Override
    public CreditLineStatus status() {
        return CreditLineStatus.FROZEN;
    }

    @Override
    public Set<CreditLineStatus> allowedTransitions() {
        return Set.of(CreditLineStatus.ACTIVE, CreditLineStatus.CLOSED, CreditLineStatus.DEFAULTED);
    }

    @Override
    public boolean allowsSpending() {
        return false;
    }
}
