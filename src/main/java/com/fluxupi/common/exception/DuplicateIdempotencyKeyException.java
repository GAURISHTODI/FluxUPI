package com.fluxupi.common.exception;

import com.fluxupi.common.FluxUpiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when the same idempotency key arrives again carrying a <em>different</em>
 * payload. A replay with an identical payload is not an error — it returns the
 * original transaction — but reusing a key for new money is a client bug we
 * must never silently accept.
 */
public class DuplicateIdempotencyKeyException extends FluxUpiException {

    public DuplicateIdempotencyKeyException(String idempotencyKey) {
        super("Idempotency key '%s' was already used with a different request payload"
                        .formatted(idempotencyKey),
                HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT");
    }
}
