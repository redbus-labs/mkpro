package com.mkpro.tools;

import com.google.adk.tools.BaseTool;

public class FileSystemTools {
    public static BaseTool create() {
        return MkProTools.createReadFileTool();
    }
}
