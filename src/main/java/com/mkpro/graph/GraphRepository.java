package com.mkpro.graph;

import java.util.Optional;

/**
 * Interface representing the persistence gateway for saving and loading 
 * parsed workspace graph extractions.
 */
public interface GraphRepository extends AutoCloseable {

    /**
     * Saves an extraction result associated with a specific version key (e.g. Git commit hash).
     */
    void saveExtraction(String commitHash, ExtractionResult extraction);

    /**
     * Loads an extraction result associated with a specific version key, if it exists.
     */
    Optional<ExtractionResult> loadExtraction(String commitHash);

    /**
     * Returns true if an extraction exists for the given version key.
     */
    boolean hasExtraction(String commitHash);

    /**
     * Deletes the stored extraction for the given version key.
     */
    void deleteExtraction(String commitHash);

    @Override
    void close();
}
