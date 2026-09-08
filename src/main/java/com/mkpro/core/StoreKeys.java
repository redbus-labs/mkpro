package com.mkpro.core;

/**
 * Constants for CentralMemory store keys and prefixes.
 * Prevents typos and enables refactoring.
 */
public final class StoreKeys {

    private StoreKeys() {} // Utility class

    // Shared store maps
    public static final String AGENT_CONFIGS = "agent_configs";
    public static final String PROJECT_GOALS = "project_goals";
    public static final String AGENT_MEMORIES = "agent_memories";
    public static final String MCP_SERVERS = "mcp_servers";
    public static final String OLLAMA_SERVERS = "ollama_servers";
    public static final String SSH_SANDBOX_CONFIG = "ssh_sandbox_config";
    public static final String FILTER_CONFIG = "filter_config";

    // Knowledge store prefix
    public static final String KNOWLEDGE_PREFIX = "knowledge:";

    // Agent names
    public static final String COORDINATOR = "Coordinator";
    public static final String GOAL_TRACKER = "GoalTracker";
}
