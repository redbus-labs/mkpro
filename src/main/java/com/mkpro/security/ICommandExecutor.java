package com.mkpro.security;

/**
 * Interface for executing shell commands with safety constraints.
 */
public interface ICommandExecutor {
    
    /**
     * Execute a command with the executor's default configuration.
     * @param command The command to execute
     * @return The result of the execution
     */
    ExecutionResult execute(String command);
    
    /**
     * Execute a command with a specific working directory.
     * @param command The command to execute
     * @param workingDir The directory to run the command in
     * @return The result of the execution
     */
    ExecutionResult execute(String command, String workingDir);
}
