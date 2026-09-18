package com.mkpro.infra.ssh;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

public class SshSessionManagerTest {

    private SshSessionManager manager;

    @BeforeEach
    void setUp() {
        manager = SshSessionManager.getInstance();
        manager.disconnectAll();
    }

    @Test
    void testSessionAliasRegistration() throws Exception {
        // Mock connection would be better, but with current structure we test the registration flow
        // For unit testing, we verify that invalid parameters or empty aliases are handled gracefully.
        
        // This triggers an exception because host/user is not provided, 
        // but we verify the alias 'default' handling
        assertThrows(Exception.class, () -> 
            manager.connect("invalid-host", 22, "user", null, null, null, null, AuthType.PASSWORD, "")
        );
        
        assertNull(manager.getSessionEntry("default"));
    }

    @Test
    void testDisconnectLifecycle() {
        // Check that calling disconnect on non-existent session doesn't throw
        assertDoesNotThrow(() -> manager.disconnect("non-existent"));
        assertFalse(manager.disconnect("non-existent"));
    }
}
