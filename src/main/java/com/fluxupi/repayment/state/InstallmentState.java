package com.fluxupi.repayment.state;

import com.fluxupi.repayment.InstallmentStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Instalment lifecycle as guarded state objects, mirroring the pattern used for
 * credit lines and transactions.
 *
 * <pre>
 *   UPCOMING ──▶ DUE ──▶ PAID
 *      │          │
 *      │          ▼
 *      └──────▶ OVERDUE ──▶ PAID
 * </pre>
 */
public interface InstallmentState {

    InstallmentStatus status();

    Set<InstallmentStatus> allowedTransitions();

    default boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    default boolean canTransitionTo(InstallmentStatus target) {
        return allowedTransitions().contains(target);
    }

    final class Registry {
        private static final Map<InstallmentStatus, InstallmentState> STATES =
                new EnumMap<>(InstallmentStatus.class);

        static {
            register(new UpcomingState());
            register(new DueState());
            register(new OverdueState());
            register(new PaidState());
            for (InstallmentStatus status : InstallmentStatus.values()) {
                if (!STATES.containsKey(status)) {
                    throw new IllegalStateException("No InstallmentState for " + status);
                }
            }
        }

        private Registry() {
        }

        private static void register(InstallmentState state) {
            STATES.put(state.status(), state);
        }

        public static InstallmentState of(InstallmentStatus status) {
            return STATES.get(status);
        }
    }
}

final class UpcomingState implements InstallmentState {
    @Override
    public InstallmentStatus status() {
        return InstallmentStatus.UPCOMING;
    }

    @Override
    public Set<InstallmentStatus> allowedTransitions() {
        return Set.of(InstallmentStatus.DUE, InstallmentStatus.OVERDUE, InstallmentStatus.PAID);
    }
}

final class DueState implements InstallmentState {
    @Override
    public InstallmentStatus status() {
        return InstallmentStatus.DUE;
    }

    @Override
    public Set<InstallmentStatus> allowedTransitions() {
        return Set.of(InstallmentStatus.OVERDUE, InstallmentStatus.PAID);
    }
}

final class OverdueState implements InstallmentState {
    @Override
    public InstallmentStatus status() {
        return InstallmentStatus.OVERDUE;
    }

    @Override
    public Set<InstallmentStatus> allowedTransitions() {
        return Set.of(InstallmentStatus.PAID);
    }
}

/** Settled in full — terminal. An instalment is never un-paid. */
final class PaidState implements InstallmentState {
    @Override
    public InstallmentStatus status() {
        return InstallmentStatus.PAID;
    }

    @Override
    public Set<InstallmentStatus> allowedTransitions() {
        return Set.of();
    }
}
