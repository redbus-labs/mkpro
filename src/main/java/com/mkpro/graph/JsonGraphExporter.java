package com.mkpro.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of GraphExporter that saves results to a JSON file.
 */
public class JsonGraphExporter implements GraphExporter {
    private final ObjectMapper objectMapper;

    public JsonGraphExporter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void export(ExtractionResult extraction, AnalysisResult analysis, Path outputPath) {
        Map<String, Object> combinedResult = new HashMap<>();
        combinedResult.put("extraction", extraction);
        combinedResult.put("analysis", analysis);

        try {
            objectMapper.writeValue(outputPath.toFile(), combinedResult);
        } catch (IOException e) {
            throw new RuntimeException("Failed to export JSON report to " + outputPath, e);
        }
    }
}
