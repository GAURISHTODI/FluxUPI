package com.fluxupi.creditline.state;

import com.fluxupi.creditline.CreditLineStatus;

import java.util.Set;

/** The only state in which a spend can be authorised. */
final class ActiveState implements CreditLineState {

    @Override
    public CreditLineStatus status() {
        return CreditLineStatus.ACTIVE;
    }

    @Override
    public Set<CreditLineStatus> allowedTransitions() {
        return Set.of(CreditLineStatus.FROZEN, CreditLineStatus.CLOSED, CreditLineStatus.DEFAULTED);
    }

    @Override
    public boolean allowsSpending() {
        return true;
    }
}
