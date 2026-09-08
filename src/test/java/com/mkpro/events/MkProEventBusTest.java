package com.mkpro.events;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MkProEventBus — event dispatch, listener management, error isolation.
 */
public class MkProEventBusTest {

    private MkProEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new MkProEventBus();
    }

    @Test
    void emptyBusHasNoListeners() {
        assertEquals(0, bus.listenerCount());
    }

    @Test
    void registerAddsListener() {
        bus.register(event -> {});
        assertEquals(1, bus.listenerCount());
    }

    @Test
    void registerNullIgnored() {
        bus.register(null);
        assertEquals(0, bus.listenerCount());
    }

    @Test
    void unregisterRemovesListener() {
        MkProEventListener listener = event -> {};
        bus.register(listener);
        assertEquals(1, bus.listenerCount());
        bus.unregister(listener);
        assertEquals(0, bus.listenerCount());
    }

    @Test
    void emitDeliversToListener() {
        List<MkProEvent> received = new ArrayList<>();
        bus.register(received::add);

        MkProEvent event = MkProEvent.system("hello");
        bus.emit(event);

        assertEquals(1, received.size());
        assertSame(event, received.get(0));
    }

    @Test
    void emitDeliversToMultipleListeners() {
        AtomicInteger count = new AtomicInteger(0);
        bus.register(event -> count.incrementAndGet());
        bus.register(event -> count.incrementAndGet());
        bus.register(event -> count.incrementAndGet());

        bus.emit(MkProEvent.system("test"));

        assertEquals(3, count.get());
    }

    @Test
    void emitNullEventIgnored() {
        AtomicInteger count = new AtomicInteger(0);
        bus.register(event -> count.incrementAndGet());
        bus.emit(null);
        assertEquals(0, count.get());
    }

    @Test
    void listenerExceptionDoesNotAffectOthers() {
        List<MkProEvent> received = new ArrayList<>();

        // First listener throws
        bus.register(event -> { throw new RuntimeException("boom"); });
        // Second listener should still receive
        bus.register(received::add);

        bus.emit(MkProEvent.system("test"));

        assertEquals(1, received.size());
    }

    @Test
    void multipleEventTypesDelivered() {
        List<MkProEvent.Type> types = new ArrayList<>();
        bus.register(event -> types.add(event.getType()));

        bus.emit(MkProEvent.system("msg"));
        bus.emit(MkProEvent.streamStart("Coder", "llama3"));
        bus.emit(MkProEvent.streamChunk("hello"));
        bus.emit(MkProEvent.streamEnd());
        bus.emit(MkProEvent.routing("DevOps", "80", "DEVOPS"));

        assertEquals(5, types.size());
        assertEquals(MkProEvent.Type.SYSTEM, types.get(0));
        assertEquals(MkProEvent.Type.STREAM_START, types.get(1));
        assertEquals(MkProEvent.Type.STREAM_CHUNK, types.get(2));
        assertEquals(MkProEvent.Type.STREAM_END, types.get(3));
        assertEquals(MkProEvent.Type.ROUTING_DECISION, types.get(4));
    }

    @Test
    void eventDataAccessible() {
        List<MkProEvent> received = new ArrayList<>();
        bus.register(received::add);

        bus.emit(MkProEvent.routing("Tester", "75", "TESTING"));

        MkProEvent event = received.get(0);
        assertEquals("Tester", event.get("agent"));
        assertEquals("75", event.get("confidence"));
        assertEquals("TESTING", event.get("category"));
    }

    @Test
    void emitAfterUnregisterNoDelivery() {
        AtomicInteger count = new AtomicInteger(0);
        MkProEventListener listener = event -> count.incrementAndGet();

        bus.register(listener);
        bus.emit(MkProEvent.system("first"));
        assertEquals(1, count.get());

        bus.unregister(listener);
        bus.emit(MkProEvent.system("second"));
        assertEquals(1, count.get()); // Still 1 — no delivery after unregister
    }
}
