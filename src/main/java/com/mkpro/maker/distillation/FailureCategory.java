package com.mkpro.maker.distillation;

/**
 * Enumeration of failure categories identified by the distillation engine.
 */
public enum FailureCategory {
    COMPILE_ERROR,
    TEST_FAILURE,
    SYNTAX_ERROR,
    SECURITY_POLICY_VIOLATION,
    FILE_NOT_FOUND,
    COMMAND_EXECUTION_FAILURE,
    TOOL_PARAMETER_ERROR,
    STALL_OR_TIMEOUT,
    UNEXPECTED_EXCEPTION,
    AGENT_PROMISE_BREACH,
    UNKNOWN
}
