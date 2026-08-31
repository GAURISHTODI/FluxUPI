package com.fluxupi.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fans a domain event out to every interested {@link DomainEventListener}.
 *
 * <p>Listeners are notified <em>after</em> the money transaction has committed
 * and are wrapped individually in try/catch. A webhook that times out must
 * never roll back a settled payment — in a real integration the lender's
 * notification is retried out of band, not folded into the payment's fate.
 */
@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final List<DomainEventListener> listeners;

    public DomainEventPublisher(List<DomainEventListener> listeners) {
        this.listeners = listeners;
    }

    public void publish(DomainEvent event) {
        for (DomainEventListener listener : listeners) {
            try {
                if (listener.supports(event)) {
                    listener.on(event);
                }
            } catch (RuntimeException e) {
                log.warn("Listener {} failed handling {} — event delivery is best-effort and the "
                        + "originating transaction is unaffected", listener.listenerName(), event.eventType(), e);
            }
        }
    }
}
