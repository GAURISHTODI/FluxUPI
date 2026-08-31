package com.fluxupi.notification;

import java.time.Instant;

/**
 * Something that happened, which interested parties may want to hear about.
 * Events are past-tense facts, never commands — a listener may not veto one.
 */
public interface DomainEvent {

    /** Stable event name, e.g. {@code transaction.settled}. */
    String eventType();

    Instant occurredAt();
}
