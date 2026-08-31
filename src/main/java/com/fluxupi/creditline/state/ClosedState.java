package com.fluxupi.creditline.state;

import com.fluxupi.creditline.CreditLineStatus;

import java.util.Set;

/** Settled and shut. Terminal — a closed line is never revived, only replaced. */
final class ClosedState implements CreditLineState {

    @Override
    public CreditLineStatus status() {
        return CreditLineStatus.CLOSED;
    }

    @Override
    public Set<CreditLineStatus> allowedTransitions() {
        return Set.of();
    }

    @Override
    public boolean allowsSpending() {
        return false;
    }
}
