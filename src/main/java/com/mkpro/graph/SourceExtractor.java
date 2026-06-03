package com.mkpro.graph;

import java.nio.file.Path;

public interface SourceExtractor {
    ExtractionResult extract(Path sourcePath);
}
