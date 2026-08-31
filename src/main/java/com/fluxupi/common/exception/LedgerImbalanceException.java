package com.fluxupi.common.exception;

import com.fluxupi.common.FluxUpiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * Raised when a journal entry does not balance. This is always a programming
 * error rather than a user error, so it is a 500 — the right response is to
 * fail the whole transaction and write nothing, never to persist a half-entry
 * and reconcile later.
 */
public class LedgerImbalanceException extends FluxUpiException {

    public LedgerImbalanceException(Object transactionRef, BigDecimal debits, BigDecimal credits) {
        super("Ledger entry for %s does not balance: debits %s != credits %s"
                        .formatted(transactionRef, debits, credits),
                HttpStatus.INTERNAL_SERVER_ERROR, "LEDGER_IMBALANCE");
    }

    public LedgerImbalanceException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, "LEDGER_IMBALANCE");
    }
}
