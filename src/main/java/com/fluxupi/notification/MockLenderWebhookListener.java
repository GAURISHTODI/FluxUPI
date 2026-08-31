package com.fluxupi.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the lender's webhook endpoint.
 *
 * <p>In a real integration this would POST to a lender-hosted URL. Here it logs
 * the delivery and keeps it in memory so tests can assert that a settlement was
 * announced — <b>no HTTP request leaves this process</b>, in keeping with the
 * project's rule that nothing touches a real payment rail.
 *
 * <p>The buffer is bounded so a long-running seed job cannot exhaust the heap.
 */
@Component
public class MockLenderWebhookListener implements DomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(MockLenderWebhookListener.class);
    private static final int MAX_RETAINED = 1_000;

    private final List<DomainEvent> delivered = new CopyOnWriteArrayList<>();

    @Override
    public boolean supports(DomainEvent event) {
        return event instanceof TransactionSettledEvent || event instanceof RepaymentDueEvent;
    }

    @Override
    public void on(DomainEvent event) {
        log.info("[mock-webhook] POST /lender/callback -> {} at {}", event.eventType(), event.occurredAt());
        delivered.add(event);
        while (delivered.size() > MAX_RETAINED) {
            delivered.remove(0);
        }
    }

    /** Everything delivered so far, most recent last. For tests and the demo endpoint. */
    public List<DomainEvent> getDelivered() {
        return List.copyOf(delivered);
    }

    public void clear() {
        delivered.clear();
    }
}
