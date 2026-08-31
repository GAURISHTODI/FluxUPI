package com.fluxupi.common;

import org.springframework.http.HttpStatus;

/**
 * Base class for every domain error FluxUPI raises on purpose.
 *
 * <p>Carrying the HTTP status and a stable machine-readable {@code errorCode}
 * on the exception itself means the REST layer never has to pattern-match on
 * exception types to decide what to return — see {@code GlobalExceptionHandler}.
 */
public abstract class FluxUpiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    protected FluxUpiException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
