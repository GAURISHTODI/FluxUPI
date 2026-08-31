package com.fluxupi.creditline.state;

import com.fluxupi.creditline.CreditLineStatus;

import java.util.Set;

/**
 * Underwriting declined the application. Terminal — a rejected applicant
 * re-applies by creating a new credit line, so the original decision and the
 * reason behind it stay on record forever.
 */
final class RejectedState implements CreditLineState {

    @Override
    public CreditLineStatus status() {
        return CreditLineStatus.REJECTED;
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
