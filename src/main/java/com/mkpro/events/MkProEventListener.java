package com.mkpro.events;

/**
 * Listener interface for mkpro events.
 * Implementations are registered on the event bus and receive all emitted events.
 */
@FunctionalInterface
public interface MkProEventListener {
    void onEvent(MkProEvent event);
}
