package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.memory.MapDBVectorStore;
import com.google.adk.memory.EmbeddingService;

public class CodebaseSearchTools {
    public static BaseTool create(MapDBVectorStore vectorStore, EmbeddingService embeddingService) {
        return MkProTools.createSearchCodebaseTool(vectorStore, embeddingService);
    }
}
