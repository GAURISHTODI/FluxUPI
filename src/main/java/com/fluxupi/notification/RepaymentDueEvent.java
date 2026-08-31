package com.fluxupi.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Emitted when an instalment becomes due, mirroring a lender's reminder webhook. */
public record RepaymentDueEvent(UUID installmentId,
                                UUID creditLineId,
                                int installmentNumber,
                                BigDecimal amountDue,
                                LocalDate dueDate,
                                Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "repayment.due";
    }
}
