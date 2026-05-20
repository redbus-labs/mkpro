package com.mkpro.tools;

import com.google.adk.tools.BaseTool;

public class ImageTools {
    public static BaseTool create() {
        return MkProTools.createReadImageTool();
    }
}
