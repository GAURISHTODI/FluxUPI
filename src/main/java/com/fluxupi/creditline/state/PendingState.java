package com.fluxupi.creditline.state;

import com.fluxupi.creditline.CreditLineStatus;

import java.util.Set;

/** Application received; underwriting decides whether it becomes APPROVED or REJECTED. */
final class PendingState implements CreditLineState {

    @Override
    public CreditLineStatus status() {
        return CreditLineStatus.PENDING;
    }

    @Override
    public Set<CreditLineStatus> allowedTransitions() {
        return Set.of(CreditLineStatus.APPROVED, CreditLineStatus.REJECTED);
    }

    @Override
    public boolean allowsSpending() {
        return false;
    }
}
