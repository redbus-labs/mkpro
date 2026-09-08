package com.mkpro.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MarkovTrainer's agent extraction from various response formats.
 * Covers synthetic training data, real session exports, and edge cases.
 */
public class MarkovTrainerTest {

    // ==========================================================================
    // Synthetic format: "[Calling ask_agent_name with instruction: ...]"
    // ==========================================================================

    @Test
    void extractsFromAskCoder() {
        String response = "I'll delegate this to the Coder.\n\n[Calling ask_coder with instruction: \"Fix the bug\"]";
        assertEquals("Coder", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskSysAdmin() {
        String response = "[Calling ask_sys_admin with instruction: \"Run the tests\"]";
        assertEquals("SysAdmin", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskGitAgent() {
        String response = "[Calling ask_git_agent with instruction: \"Commit changes\"]";
        assertEquals("GitAgent", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskSecurityAuditor() {
        String response = "Delegating.\n\n[Calling ask_security_auditor with instruction: \"Scan\"]";
        assertEquals("SecurityAuditor", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskDevOps() {
        String response = "[Calling ask_dev_ops with instruction: \"Deploy\"]";
        assertEquals("DevOps", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskDataAnalyst() {
        String response = "[Calling ask_data_analyst with instruction: \"Analyze CSV\"]";
        assertEquals("DataAnalyst", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskAndroidDev() {
        String response = "[Calling ask_android_dev with instruction: \"Build UI\"]";
        assertEquals("AndroidDev", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskIosDev() {
        String response = "[Calling ask_ios_dev with instruction: \"Create view\"]";
        assertEquals("IosDev", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskDocWriter() {
        String response = "[Calling ask_doc_writer with instruction: \"Update README\"]";
        assertEquals("DocWriter", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskGoalTracker() {
        String response = "[Calling ask_goal_tracker with instruction: \"Track progress\"]";
        assertEquals("GoalTracker", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskDatabaseAdmin() {
        String response = "[Calling ask_database_admin with instruction: \"Write migration\"]";
        assertEquals("DatabaseAdmin", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskArchitect() {
        String response = "[Calling ask_architect with instruction: \"Review design\"]";
        assertEquals("Architect", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromAskCodeEditor() {
        String response = "[Calling ask_code_editor with instruction: \"Apply changes\"]";
        assertEquals("CodeEditor", MarkovTrainer.extractAgent(response));
    }

    // ==========================================================================
    // Real session format: ">> Delegating to AgentName..."
    // ==========================================================================

    @Test
    void extractsFromDirectDelegationSysAdmin() {
        String response = ">> Delegating to SysAdmin...\n[Shell] $ ls -la";
        assertEquals("SysAdmin", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromDirectDelegationCoder() {
        String response = "Some preamble\n>> Delegating to Coder...\nHere's the code...";
        assertEquals("Coder", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromDirectDelegationArchitect() {
        String response = ">> Delegating to Architect...\nThe architecture looks...";
        assertEquals("Architect", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromDirectDelegationTester() {
        String response = ">> Delegating to Tester...\nRunning tests...";
        assertEquals("Tester", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromDirectDelegationGitAgent() {
        String response = ">> Delegating to GitAgent...\n[Shell] $ git status";
        assertEquals("GitAgent", MarkovTrainer.extractAgent(response));
    }

    // ==========================================================================
    // Natural language format: "I'll delegate to the Architect"
    // ==========================================================================

    @Test
    void extractsFromNaturalDelegate() {
        String response = "I'll delegate this to the Architect for review.";
        assertEquals("Architect", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromDelegatingTo() {
        String response = "Delegating to the SecurityAuditor for a scan.";
        assertEquals("SecurityAuditor", MarkovTrainer.extractAgent(response));
    }

    @Test
    void extractsFromRouteTo() {
        String response = "I'll route this to the Tester.";
        assertEquals("Tester", MarkovTrainer.extractAgent(response));
    }

    // ==========================================================================
    // Agent name normalization
    // ==========================================================================

    @ParameterizedTest
    @CsvSource({
        "coder, Coder",
        "sys_admin, SysAdmin",
        "git_agent, GitAgent",
        "code_editor, CodeEditor",
        "security_auditor, SecurityAuditor",
        "database_admin, DatabaseAdmin",
        "dev_ops, DevOps",
        "data_analyst, DataAnalyst",
        "android_dev, AndroidDev",
        "ios_dev, IosDev",
        "doc_writer, DocWriter",
        "goal_tracker, GoalTracker",
        "architect, Architect",
        "tester, Tester",
        "coordinator, Coordinator",
    })
    void normalizesAgentNames(String input, String expected) {
        assertEquals(expected, MarkovTrainer.normalizeAgentName(input));
    }

    @Test
    void normalizesWithAskPrefix() {
        assertEquals("Coder", MarkovTrainer.normalizeAgentName("ask_coder"));
        assertEquals("SysAdmin", MarkovTrainer.normalizeAgentName("ask_sys_admin"));
    }

    // ==========================================================================
    // Edge cases
    // ==========================================================================

    @Test
    void returnsNullForNoMatch() {
        String response = "I don't know how to help with that.";
        assertNull(MarkovTrainer.extractAgent(response));
    }

    @Test
    void returnsNullForNull() {
        assertNull(MarkovTrainer.extractAgent(null));
    }

    @Test
    void returnsNullForEmpty() {
        assertNull(MarkovTrainer.extractAgent(""));
    }

    @Test
    void handlesMultiplePatternsPicksFirst() {
        // ask_ pattern should win over >> Delegating pattern
        String response = "[Calling ask_coder with instruction: \"fix\"]\n>> Delegating to Tester...";
        assertEquals("Coder", MarkovTrainer.extractAgent(response));
    }

    @Test
    void handlesResponseWithOnlyAgentOutput() {
        // No delegation pattern — just agent output
        String response = "Here is the code:\n```java\npublic class Foo {}\n```";
        assertNull(MarkovTrainer.extractAgent(response));
    }
}
