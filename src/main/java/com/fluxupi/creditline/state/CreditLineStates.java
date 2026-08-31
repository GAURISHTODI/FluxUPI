package com.fluxupi.creditline.state;

import com.fluxupi.creditline.CreditLineStatus;

import java.util.EnumMap;
import java.util.Map;

/**
 * The single place that maps a persisted {@link CreditLineStatus} to its state
 * object. States are stateless and immutable, so one instance each is shared.
 *
 * <p>The static initialiser asserts the registry is complete, which turns
 * "someone added an enum constant and forgot the state class" from a runtime
 * NullPointerException into a failure at class-load time.
 */
public final class CreditLineStates {

    private static final Map<CreditLineStatus, CreditLineState> REGISTRY = new EnumMap<>(CreditLineStatus.class);

    static {
        register(new PendingState());
        register(new ApprovedState());
        register(new RejectedState());
        register(new ActiveState());
        register(new FrozenState());
        register(new ClosedState());
        register(new DefaultedState());

        for (CreditLineStatus status : CreditLineStatus.values()) {
            if (!REGISTRY.containsKey(status)) {
                throw new IllegalStateException("No CreditLineState registered for " + status);
            }
        }
    }

    private CreditLineStates() {
    }

    private static void register(CreditLineState state) {
        REGISTRY.put(state.status(), state);
    }

    public static CreditLineState of(CreditLineStatus status) {
        CreditLineState state = REGISTRY.get(status);
        if (state == null) {
            throw new IllegalStateException("No CreditLineState registered for " + status);
        }
        return state;
    }
}
