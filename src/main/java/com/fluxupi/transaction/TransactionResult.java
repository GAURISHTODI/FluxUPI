package com.fluxupi.transaction;

/**
 * The outcome of a transaction request.
 *
 * @param replayed true when this is the stored result of an earlier identical
 *                 request rather than fresh work. The API surfaces this so a
 *                 client can tell "your retry was absorbed" from "we processed
 *                 it again" — the distinction that makes idempotency
 *                 observable rather than merely claimed.
 */
public record TransactionResult(Transaction transaction, boolean replayed) {

    public static TransactionResult processed(Transaction transaction) {
        return new TransactionResult(transaction, false);
    }

    public static TransactionResult replayed(Transaction transaction) {
        return new TransactionResult(transaction, true);
    }
}
