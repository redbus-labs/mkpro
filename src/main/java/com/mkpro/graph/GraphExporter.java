package com.mkpro.graph;

import java.nio.file.Path;

/**
 * Interface for exporting graph analysis results.
 */
public interface GraphExporter {
    /**
     * Exports the results of project extraction and analysis.
     *
     * @param extraction The result of the project scanning/extraction phase.
     * @param analysis   The result of the graph analysis phase.
     * @param outputPath The path where the export should be saved.
     */
    void export(ExtractionResult extraction, AnalysisResult analysis, Path outputPath);
}
