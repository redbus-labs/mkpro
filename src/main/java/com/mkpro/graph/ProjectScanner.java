package com.mkpro.graph;

import java.nio.file.Path;

/**
 * Interface for scanning a project and extracting AST information.
 */
public interface ProjectScanner {
    /**
     * Scans the project at the given root path and returns the extraction results.
     * 
     * @param projectRoot The root directory of the project to scan.
     * @return ExtractionResult containing the entities and relationships found.
     */
    ExtractionResult scan(Path projectRoot);
}
