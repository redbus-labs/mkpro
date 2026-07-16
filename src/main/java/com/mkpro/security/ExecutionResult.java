package com.mkpro.security;

/**
 * Result of a shell execution containing stdout, stderr, exit code, and metadata.
 */
public class ExecutionResult {
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
