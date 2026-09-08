package com.mkpro.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import java.util.regex.Pattern;

public class RemoteCommandPolicyTest {

    private CommandPolicy policy;

    @BeforeEach
    void setUp() {
        policy = CommandPolicy.getInstance();
    }

    @Test
    void testAllowlistValidation() {
        assertTrue(policy.evaluate("ls -la").isAllowed());
        assertTrue(policy.evaluate("git status").isAllowed());
        assertFalse(policy.evaluate("rm -rf /").isAllowed());
        assertFalse(policy.evaluate("hack_command").isAllowed());
    }

    @Test
    void testDangerousCommandBlocking() {
        assertFalse(policy.evaluate("rm -rf /").isAllowed());
        assertFalse(policy.evaluate("rm -fr /").isAllowed());
        assertFalse(policy.evaluate("git push --force origin main").isAllowed());
    }
}
