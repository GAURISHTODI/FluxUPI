package com.fluxupi.common.exception;

import com.fluxupi.common.FluxUpiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when something tries to move an entity between two states that its
 * state machine does not connect — e.g. reviving a {@code CLOSED} credit line
 * back to {@code ACTIVE}.
 */
public class IllegalStateTransitionException extends FluxUpiException {

    public IllegalStateTransitionException(String entity, Object from, Object to) {
        super("%s cannot transition from %s to %s".formatted(entity, from, to),
                HttpStatus.CONFLICT, "ILLEGAL_STATE_TRANSITION");
    }
}
