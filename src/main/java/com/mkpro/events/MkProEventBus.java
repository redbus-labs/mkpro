package com.mkpro.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central event bus for mkpro. All components emit events here;
 * registered listeners (sinks) receive them asynchronously.
 * 
 * Thread-safe. Dispatch is synchronous (listeners run on emitter's thread).
 * Listeners must not block or throw — failures in one sink don't affect others.
 */
public class MkProEventBus {

    private final List<MkProEventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Register a listener (sink) to receive all events.
     */
    public void register(MkProEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Unregister a listener.
     */
    public void unregister(MkProEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Emit an event to all registered listeners.
     * Exceptions in listeners are caught and ignored (one sink failure doesn't affect others).
     */
    public void emit(MkProEvent event) {
        if (event == null) return;
        for (MkProEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                // Silent — one sink failure must not crash the emitter
            }
        }
    }

    /**
     * Number of registered listeners.
     */
    public int listenerCount() {
        return listeners.size();
    }
}
