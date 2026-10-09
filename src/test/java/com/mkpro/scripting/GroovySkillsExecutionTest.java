package com.mkpro.scripting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.HashMap;

public class GroovySkillsExecutionTest {

    private static final String SKILLS_ROOT = "src/main/resources/teams/polyglot/skills/";

    @TempDir
    Path tempDir;

    @Test
    void testWriteFile() throws Exception {
        String writeCode = Files.readString(Path.of(SKILLS_ROOT, "file_operations/scripts/write_file.groovy"));
        Map<String, Object> writeArgs = new HashMap<>();
        writeArgs.put("path", tempDir.resolve("test.txt").toString());
        writeArgs.put("content", "Hello World");
        
        ScriptEngine.ScriptResult writeResult = ScriptEngine.execute(writeCode, writeArgs);
        assertTrue(writeResult.success, "Write failed: " + (writeResult.error != null ? writeResult.error : ""));
        assertTrue(Files.exists(tempDir.resolve("test.txt")));
        assertEquals("Hello World", Files.readString(tempDir.resolve("test.txt")));
    }

    @Test
    void testReadFile() throws Exception {
        Path testFile = tempDir.resolve("read_test.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3");
        
        String readCode = Files.readString(Path.of(SKILLS_ROOT, "file_operations/scripts/read_file.groovy"));
        Map<String, Object> readArgs = new HashMap<>();
        readArgs.put("path", testFile.toString());
        readArgs.put("start_line", 1);
        readArgs.put("end_line", 1);
        
        ScriptEngine.ScriptResult readResult = ScriptEngine.execute(readCode, readArgs);
        assertTrue(readResult.success, "Read failed: " + (readResult.error != null ? readResult.error : ""));
        assertNotNull(readResult.returnValue);
    }
}
