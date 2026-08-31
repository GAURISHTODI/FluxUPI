package com.fluxupi.creditline.state;

import com.fluxupi.creditline.CreditLineStatus;

import java.util.Set;

/**
 * One state in the credit line lifecycle, as an object rather than an enum
 * constant with {@code if} chains scattered across services.
 *
 * <p>Each implementation answers three questions about itself: what states it
 * may move to, whether money can be spent while in it, and whether it is the
 * end of the road. Adding a state means adding one class and registering it in
 * {@link CreditLineStates} — no existing service changes.
 */
public interface CreditLineState {

    /** The persisted label for this state. */
    CreditLineStatus status();

    /** Every state this one may legally move to. */
    Set<CreditLineStatus> allowedTransitions();

    /** True when a spend may be authorised while the line is in this state. */
    boolean allowsSpending();

    /** True when no further transition is possible from here. */
    default boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /**
     * The guard every transition must pass. Note that a state is never allowed
     * to transition to itself — re-applying the same status is a no-op the
     * caller should not be silently granted.
     */
    default boolean canTransitionTo(CreditLineStatus target) {
        return allowedTransitions().contains(target);
    }
}
