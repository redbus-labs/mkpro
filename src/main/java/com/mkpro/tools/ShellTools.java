package com.mkpro.tools;

import com.google.adk.tools.BaseTool;

public class ShellTools {
    public static BaseTool create() {
        return MkProTools.createRunShellTool();
    }

    public static String runCommand(String command) {
        com.mkpro.security.ShellExecutor executor = new com.mkpro.security.ShellExecutor(120, 100 * 1024);
        com.mkpro.security.ShellExecutor.ExecutionResult result = executor.execute(command);
        return result.toAgentResponse();
    }
}
