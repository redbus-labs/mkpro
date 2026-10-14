package com.mkpro.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class InstructionSanitizerTest {

    @Test
    public void testSanitizeBasicIdentifiers() {
        assertEquals("[id]", InstructionSanitizer.sanitize("{id}"));
        assertEquals("[total]", InstructionSanitizer.sanitize("{total}"));
        assertEquals("[var]", InstructionSanitizer.sanitize("{var?}"));
        assertEquals("[user:name]", InstructionSanitizer.sanitize("{user:name}"));
    }

    @Test
    public void testJsonPreservation() {
        String json = "{\"name\": \"test\", \"id\": 123}";
        assertEquals(json, InstructionSanitizer.sanitize(json));
    }

    @Test
    public void testComplexInstruction() {
        String input = "Please use {id} and {status?} for {task:action}. Here is a JSON: {\"key\": \"value\"}";
        String expected = "Please use [id] and [status] for [task:action]. Here is a JSON: {\"key\": \"value\"}";
        assertEquals(expected, InstructionSanitizer.sanitize(input));
    }

    @Test
    public void testNullOrEmpty() {
        assertNull(InstructionSanitizer.sanitize(null));
        assertEquals("", InstructionSanitizer.sanitize(""));
    }
}
