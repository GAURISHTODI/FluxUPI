package com.fluxupi.transaction.state;

import com.fluxupi.transaction.TransactionStatus;

import java.util.Set;

/**
 * One state in the transaction lifecycle. Same pattern as
 * {@link com.fluxupi.creditline.state.CreditLineState}: guards live on the
 * state object, never as {@code if (status == ...)} in a service.
 */
public interface TransactionState {

    TransactionStatus status();

    Set<TransactionStatus> allowedTransitions();

    /** True when this transaction's ledger entries count towards live balances. */
    boolean isSettled();

    default boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    default boolean canTransitionTo(TransactionStatus target) {
        return allowedTransitions().contains(target);
    }
}
