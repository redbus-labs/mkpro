package com.mkpro.tools;

import com.google.adk.tools.BaseTool;

public class ShellTools {
    public static BaseTool create() {
        return MkProTools.createRunShellTool();
    }
}
