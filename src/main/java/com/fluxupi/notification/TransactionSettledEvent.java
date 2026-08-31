package com.fluxupi.notification;

import com.fluxupi.transaction.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted after a transaction commits. Carries plain values rather than the
 * entity, so a listener cannot accidentally mutate domain state or trip over a
 * detached lazy association.
 */
public record TransactionSettledEvent(UUID transactionId,
                                      UUID creditLineId,
                                      String transactionType,
                                      String status,
                                      BigDecimal amount,
                                      Instant occurredAt) implements DomainEvent {

    public static TransactionSettledEvent from(Transaction transaction) {
        return new TransactionSettledEvent(
                transaction.getId(),
                transaction.getCreditLine().getId(),
                transaction.getType().name(),
                transaction.getStatus().name(),
                transaction.getAmount(),
                Instant.now());
    }

    @Override
    public String eventType() {
        return "transaction.settled";
    }
}
