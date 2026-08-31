package com.fluxupi.notification;

/**
 * An observer of domain events. Implementations are discovered by Spring and
 * registered automatically with {@link DomainEventPublisher}.
 */
public interface DomainEventListener {

    /** Whether this listener wants the given event. */
    boolean supports(DomainEvent event);

    void on(DomainEvent event);

    /** Name used in logs. Defaults to the simple class name. */
    default String listenerName() {
        return getClass().getSimpleName();
    }
}
