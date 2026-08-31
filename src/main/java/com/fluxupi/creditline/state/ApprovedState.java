package com.fluxupi.creditline.state;

import com.fluxupi.creditline.CreditLineStatus;

import java.util.Set;

/**
 * A limit has been offered but not switched on. The user still has to activate
 * it; if they never do, the line is closed rather than left dangling. Spending
 * is deliberately not permitted here — approval alone is not authorisation.
 */
final class ApprovedState implements CreditLineState {

    @Override
    public CreditLineStatus status() {
        return CreditLineStatus.APPROVED;
    }

    @Override
    public Set<CreditLineStatus> allowedTransitions() {
        return Set.of(CreditLineStatus.ACTIVE, CreditLineStatus.CLOSED);
    }

    @Override
    public boolean allowsSpending() {
        return false;
    }
}
