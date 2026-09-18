package com.mkpro.tools;

import com.google.adk.tools.BaseTool;

public class ClipboardTools {
    public static BaseTool create() {
        return MkProTools.createReadClipboardTool();
    }
}
