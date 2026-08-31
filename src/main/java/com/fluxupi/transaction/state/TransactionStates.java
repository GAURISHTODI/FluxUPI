package com.fluxupi.transaction.state;

import com.fluxupi.transaction.TransactionStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Registry of the four transaction states. See {@link TransactionState}. */
public final class TransactionStates {

    private static final Map<TransactionStatus, TransactionState> REGISTRY =
            new EnumMap<>(TransactionStatus.class);

    static {
        register(new InitiatedState());
        register(new SuccessState());
        register(new FailedState());
        register(new ReversedState());

        for (TransactionStatus status : TransactionStatus.values()) {
            if (!REGISTRY.containsKey(status)) {
                throw new IllegalStateException("No TransactionState registered for " + status);
            }
        }
    }

    private TransactionStates() {
    }

    private static void register(TransactionState state) {
        REGISTRY.put(state.status(), state);
    }

    public static TransactionState of(TransactionStatus status) {
        TransactionState state = REGISTRY.get(status);
        if (state == null) {
            throw new IllegalStateException("No TransactionState registered for " + status);
        }
        return state;
    }
}

/** In flight. The only state from which a transaction can still be decided. */
final class InitiatedState implements TransactionState {

    @Override
    public TransactionStatus status() {
        return TransactionStatus.INITIATED;
    }

    @Override
    public Set<TransactionStatus> allowedTransitions() {
        return Set.of(TransactionStatus.SUCCESS, TransactionStatus.FAILED);
    }

    @Override
    public boolean isSettled() {
        return false;
    }
}

/** Committed. Its ledger entries are live and count towards balances. */
final class SuccessState implements TransactionState {

    @Override
    public TransactionStatus status() {
        return TransactionStatus.SUCCESS;
    }

    @Override
    public Set<TransactionStatus> allowedTransitions() {
        return Set.of(TransactionStatus.REVERSED);
    }

    @Override
    public boolean isSettled() {
        return true;
    }
}

/**
 * Rejected before anything was written. Terminal — a failed transaction is
 * never retried in place; the client sends a new idempotency key.
 */
final class FailedState implements TransactionState {

    @Override
    public TransactionStatus status() {
        return TransactionStatus.FAILED;
    }

    @Override
    public Set<TransactionStatus> allowedTransitions() {
        return Set.of();
    }

    @Override
    public boolean isSettled() {
        return false;
    }
}

/**
 * Unwound by a REVERSAL. Terminal, and note that both the original entries and
 * the reversing entries stay on the ledger — an audit trail is append-only, so
 * nothing is ever deleted to "undo" a posting.
 */
final class ReversedState implements TransactionState {

    @Override
    public TransactionStatus status() {
        return TransactionStatus.REVERSED;
    }

    @Override
    public Set<TransactionStatus> allowedTransitions() {
        return Set.of();
    }

    @Override
    public boolean isSettled() {
        return true;
    }
}
