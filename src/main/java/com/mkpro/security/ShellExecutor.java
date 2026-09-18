package com.mkpro.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * ShellExecutor provides a hardened process execution layer with:
 * - Configurable timeout (default 120s)
 * - Output size limits (default 100KB)
 * - Stderr capture
 * - Working directory control
 * - Integration with CommandPolicy for allowlist enforcement
 */
public class ShellExecutor {

    /**
     * Result of a shell execution containing stdout, stderr, exit code, and metadata.
     */
    public static class ExecutionResult {
        private final String stdout;
        private final String stderr;
        private final int exitCode;
        private final boolean timedOut;
        private final boolean outputTruncated;
        private final long durationMs;

        public ExecutionResult(String stdout, String stderr, int exitCode, 
                              boolean timedOut, boolean outputTruncated, long durationMs) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.outputTruncated = outputTruncated;
            this.durationMs = durationMs;
        }

        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public int getExitCode() { return exitCode; }
        public boolean isTimedOut() { return timedOut; }
        public boolean isOutputTruncated() { return outputTruncated; }
        public long getDurationMs() { return durationMs; }

        public boolean isSuccess() { return exitCode == 0 && !timedOut; }

        /**
         * Returns a formatted string suitable for returning to an agent.
         */
        public String toAgentResponse() {
            StringBuilder sb = new StringBuilder();
            if (timedOut) {
                sb.append("[TIMEOUT] Process was killed after exceeding time limit.\n");
            }
            if (outputTruncated) {
                sb.append("[TRUNCATED] Output exceeded size limit and was truncated.\n");
            }
            if (!stdout.isEmpty()) {
                sb.append(stdout);
            }
            if (!stderr.isEmpty()) {
                if (!stdout.isEmpty()) sb.append("\n");
                sb.append("[STDERR]\n").append(stderr);
            }
            if (!timedOut) {
                sb.append("\n[Exit Code: ").append(exitCode).append("]");
            }
            return sb.toString();
        }
    }

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 100 * 1024; // 100KB

    private final int timeoutSeconds;
    private final int maxOutputBytes;

    public ShellExecutor() {
        this(DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_OUTPUT_BYTES);
    }

    public ShellExecutor(int timeoutSeconds, int maxOutputBytes) {
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * Execute a command with full safety checks.
     *
     * @param command The shell command to execute
     * @param workingDir Optional working directory (null = current directory)
     * @return ExecutionResult with stdout, stderr, exit code, and metadata
     */
    public ExecutionResult execute(String command, String workingDir) {
        // 1. Policy check
        CommandPolicy.PolicyResult policyResult = CommandPolicy.getInstance().evaluateChained(command);
        if (!policyResult.isAllowed()) {
            return new ExecutionResult(
                "", 
                "BLOCKED: " + policyResult.getReason(), 
                -1, false, false, 0
            );
        }

        long startTime = System.currentTimeMillis();

        try {
            // 2. Build process
            ProcessBuilder pb = buildProcess(command);
            
            // Set working directory if specified
            if (workingDir != null && !workingDir.isBlank()) {
                Path dir = Paths.get(workingDir);
                if (Files.isDirectory(dir)) {
                    pb.directory(dir.toFile());
                } else {
                    return new ExecutionResult(
                        "", "Working directory does not exist: " + workingDir,
                        -1, false, false, 0
                    );
                }
            }

            // 3. Start process
            Process process = pb.start();

            // 4. Read output with size limits (in separate threads to avoid deadlock)
            OutputReader stdoutReader = new OutputReader(process.getInputStream(), maxOutputBytes);
            OutputReader stderrReader = new OutputReader(process.getErrorStream(), maxOutputBytes);

            Thread stdoutThread = new Thread(stdoutReader, "shell-stdout-reader");
            Thread stderrThread = new Thread(stderrReader, "shell-stderr-reader");
            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
            stdoutThread.start();
            stderrThread.start();

            // 5. Wait with timeout
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                // Timeout — kill the process tree
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS); // Grace period for cleanup

                // Wait for readers to finish
                stdoutThread.join(2000);
                stderrThread.join(2000);

                long duration = System.currentTimeMillis() - startTime;
                return new ExecutionResult(
                    stdoutReader.getOutput(),
                    stderrReader.getOutput(),
                    -1, true,
                    stdoutReader.isTruncated() || stderrReader.isTruncated(),
                    duration
                );
            }

            // 6. Process completed normally
            stdoutThread.join(5000);
            stderrThread.join(5000);

            long duration = System.currentTimeMillis() - startTime;
            return new ExecutionResult(
                stdoutReader.getOutput(),
                stderrReader.getOutput(),
                process.exitValue(),
                false,
                stdoutReader.isTruncated() || stderrReader.isTruncated(),
                duration
            );

        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            return new ExecutionResult(
                "", "Process execution failed: " + e.getMessage(),
                -1, false, false, duration
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long duration = System.currentTimeMillis() - startTime;
            return new ExecutionResult(
                "", "Process execution interrupted: " + e.getMessage(),
                -1, false, false, duration
            );
        }
    }

    /**
     * Execute with default working directory.
     */
    public ExecutionResult execute(String command) {
        return execute(command, null);
    }

    private ProcessBuilder buildProcess(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            return new ProcessBuilder("sh", "-c", command);
        }
    }

    /**
     * Thread-safe output reader that respects size limits.
     */
    private static class OutputReader implements Runnable {
        private final InputStream inputStream;
        private final int maxBytes;
        private final StringBuilder buffer = new StringBuilder();
        private volatile boolean truncated = false;

        OutputReader(InputStream inputStream, int maxBytes) {
            this.inputStream = inputStream;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                char[] charBuf = new char[4096];
                int bytesRead = 0;
                int charsRead;

                while ((charsRead = reader.read(charBuf)) != -1) {
                    int remaining = maxBytes - bytesRead;
                    if (remaining <= 0) {
                        truncated = true;
                        // Drain remaining to prevent process blocking
                        while (reader.read(charBuf) != -1) { /* discard */ }
                        break;
                    }

                    int toAppend = Math.min(charsRead, remaining);
                    buffer.append(charBuf, 0, toAppend);
                    bytesRead += toAppend;

                    if (toAppend < charsRead) {
                        truncated = true;
                        // Drain remaining
                        while (reader.read(charBuf) != -1) { /* discard */ }
                        break;
                    }
                }
            } catch (IOException e) {
                buffer.append("\n[Stream read error: ").append(e.getMessage()).append("]");
            }
        }

        public String getOutput() {
            String result = buffer.toString();
            if (truncated) {
                result += "\n... [output truncated at " + maxBytes + " bytes]";
            }
            return result;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }
}
