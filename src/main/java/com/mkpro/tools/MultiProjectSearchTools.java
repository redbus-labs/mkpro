package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.memory.EmbeddingService;
import com.google.adk.memory.MapDBVectorStore;

public class MultiProjectSearchTools {
    public static BaseTool create(EmbeddingService embeddingService, MapDBVectorStore vectorStore) {
        return MkProTools.createMultiProjectSearchTool(embeddingService, vectorStore);
    }
}
