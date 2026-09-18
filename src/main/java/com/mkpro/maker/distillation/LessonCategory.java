package com.mkpro.maker.distillation;

/**
 * Enumeration of lesson failure categories.
 */
public enum LessonCategory {
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
    UNKNOWN;

    public FailureCategory toFailureCategory() {
        try {
            return FailureCategory.valueOf(this.name());
        } catch (Exception e) {
            return FailureCategory.UNKNOWN;
        }
    }

    public static LessonCategory fromFailureCategory(FailureCategory category) {
        if (category == null) {
            return UNKNOWN;
        }
        try {
            return LessonCategory.valueOf(category.name());
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
