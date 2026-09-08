package com.mkpro.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import java.nio.file.Paths;

public class RemotePathValidatorTest {

    private PathValidator validator;

    @BeforeEach
    void setUp() {
        validator = PathValidator.initialize(Paths.get(System.getProperty("user.dir")), null);
    }

    @Test
    void testPathTraversalProtection() {
        // Valid path within CWD
        assertDoesNotThrow(() -> validator.validate("src/main/java"));
        
        // Potential traversal
        assertThrows(SecurityException.class, () -> validator.validate("../../../etc/passwd"));
    }

    @Test
    void testBlockedPatterns() {
        // Sensitive files
        assertThrows(SecurityException.class, () -> validator.validate("id_rsa"));
        assertThrows(SecurityException.class, () -> validator.validate(".ssh/config"));
    }
}
