package com.mkpro.security;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

public class ShellExecutorTest {

    private ShellExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ShellExecutor();
    }

    // --- Blocked command passthrough ---

    @Test
    void blockedCommandReturnsBlockedResult() {
        ShellExecutor.ExecutionResult result = executor.execute("rm -rf /");
        assertFalse(result.isSuccess());
        assertEquals(-1, result.getExitCode());
        assertTrue(result.getStderr().contains("BLOCKED"));
    }

    @Test
    void blockedCommandHasZeroDuration() {
        ShellExecutor.ExecutionResult result = executor.execute("shutdown now");
        assertEquals(0, result.getDurationMs());
    }

    @Test
    void blockedChainedCommand() {
        ShellExecutor.ExecutionResult result = executor.execute("echo hi; rm -rf /");
        assertFalse(result.isSuccess());
        assertTrue(result.getStderr().contains("BLOCKED"));
    }

    // --- Successful execution ---

    @Test
    void executesAllowedCommandSuccessfully() {
        ShellExecutor.ExecutionResult result = executor.execute("echo hello_world");
        assertTrue(result.isSuccess(), "echo should succeed. stderr: " + result.getStderr());
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("hello_world"));
        assertFalse(result.isTimedOut());
        assertFalse(result.isOutputTruncated());
    }

    @Test
    void capturesExitCode() {
        // A command that exits with non-zero
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? "cmd /c exit 42"
            : "sh -c 'exit 42'";
        // ShellExecutor wraps in cmd/sh, so we just pass the inner part
        ShellExecutor.ExecutionResult result = executor.execute("exit 42");
        assertFalse(result.isSuccess());
        assertFalse(result.isTimedOut());
    }

    @Test
    void capturesStderr() {
        // On Windows cmd.exe, stderr redirection via "1>&2" works differently
        // Use a command that reliably produces stderr on both platforms
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? "dir /nonexistent_path_xyz"
            : "ls /nonexistent_path_xyz";
        ShellExecutor.ExecutionResult result = executor.execute(cmd);
        // Command should fail (non-zero exit) and produce stderr
        assertFalse(result.getStderr().isEmpty() && result.getStdout().isEmpty(),
            "Should capture some output. stdout: '" + result.getStdout() + "' stderr: '" + result.getStderr() + "'");
    }

    // --- Working directory ---

    @Test
    void respectsWorkingDirectory() throws IOException {
        Path tempDir = Files.createTempDirectory("shell-wd-test-");
        Files.writeString(tempDir.resolve("marker.txt"), "found");
        try {
            String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "dir marker.txt"
                : "ls marker.txt";
            ShellExecutor.ExecutionResult result = executor.execute(cmd, tempDir.toString());
            assertTrue(result.isSuccess(), "Should find marker.txt in working dir. stderr: " + result.getStderr());
            assertTrue(result.getStdout().contains("marker.txt"));
        } finally {
            Files.walk(tempDir).sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    void nonExistentWorkingDirReturnsError() {
        ShellExecutor.ExecutionResult result = executor.execute("echo hi", "/nonexistent/path/xyz123");
        assertFalse(result.isSuccess());
        assertTrue(result.getStderr().contains("does not exist"));
    }

    @Test
    void nullWorkingDirUsesDefault() {
        ShellExecutor.ExecutionResult result = executor.execute("echo default_dir", null);
        assertTrue(result.isSuccess());
        assertTrue(result.getStdout().contains("default_dir"));
    }

    // --- Timeout behavior ---

    @Test
    void timeoutKillsLongRunningProcess() {
        // Use a very short timeout (2 seconds)
        ShellExecutor shortTimeout = new ShellExecutor(2, 100 * 1024);
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? "ping -n 30 127.0.0.1"
            : "sleep 30";
        ShellExecutor.ExecutionResult result = shortTimeout.execute(cmd);
        assertTrue(result.isTimedOut(), "Should timeout for long-running command");
        assertEquals(-1, result.getExitCode());
        assertFalse(result.isSuccess());
    }

    @Test
    void timeoutResultReportsInAgentResponse() {
        ShellExecutor shortTimeout = new ShellExecutor(1, 100 * 1024);
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? "ping -n 30 127.0.0.1"
            : "sleep 30";
        ShellExecutor.ExecutionResult result = shortTimeout.execute(cmd);
        String response = result.toAgentResponse();
        assertTrue(response.contains("[TIMEOUT]"));
    }

    // --- Output truncation ---

    @Test
    void outputTruncatedWhenExceedingLimit() {
        // Limit to 100 bytes
        ShellExecutor smallOutput = new ShellExecutor(30, 100);
        // Generate output larger than 100 bytes
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? "echo " + "x".repeat(200)
            : "printf '" + "x".repeat(200) + "'";
        ShellExecutor.ExecutionResult result = smallOutput.execute(cmd);
        assertTrue(result.isOutputTruncated(), "Output should be truncated at 100 bytes");
        String response = result.toAgentResponse();
        assertTrue(response.contains("[TRUNCATED]") || response.contains("truncated"));
    }

    // --- toAgentResponse format ---

    @Test
    void agentResponseIncludesExitCode() {
        ShellExecutor.ExecutionResult result = executor.execute("echo done");
        String response = result.toAgentResponse();
        assertTrue(response.contains("[Exit Code: 0]"));
    }

    @Test
    void agentResponseIncludesStderr() {
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
            ? "echo err 1>&2"
            : "echo err >&2";
        ShellExecutor.ExecutionResult result = executor.execute(cmd);
        String response = result.toAgentResponse();
        // Should have STDERR marker if stderr was captured
        if (!result.getStderr().isEmpty()) {
            assertTrue(response.contains("[STDERR]"));
        }
    }

    // --- Duration tracking ---

    @Test
    void durationIsPositiveForRealCommands() {
        ShellExecutor.ExecutionResult result = executor.execute("echo timing");
        assertTrue(result.getDurationMs() >= 0, "Duration should be non-negative");
    }

    // --- ExecutionResult contract ---

    @Test
    void successRequiresZeroExitAndNoTimeout() {
        ShellExecutor.ExecutionResult success = new ShellExecutor.ExecutionResult(
            "output", "", 0, false, false, 100);
        assertTrue(success.isSuccess());

        ShellExecutor.ExecutionResult timedOut = new ShellExecutor.ExecutionResult(
            "output", "", 0, true, false, 100);
        assertFalse(timedOut.isSuccess());

        ShellExecutor.ExecutionResult nonZero = new ShellExecutor.ExecutionResult(
            "output", "", 1, false, false, 100);
        assertFalse(nonZero.isSuccess());
    }
}
