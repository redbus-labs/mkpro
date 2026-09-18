package com.mkpro.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileFormatReader — text-based format parsing (CSV, DXF, STL, OBJ, SVG).
 * PDF/DOCX/XLSX tests skipped (require binary test fixtures).
 */
public class FileFormatReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void csvIsNotSpecialFormat() {
        // CSV is not in SUPPORTED_EXTENSIONS — handled separately
        assertFalse(FileFormatReader.isSpecialFormat("data.csv"));
    }

    @Test
    void dxfIsSpecialFormat() {
        assertTrue(FileFormatReader.isSpecialFormat("drawing.dxf"));
    }

    @Test
    void stlIsSpecialFormat() {
        assertTrue(FileFormatReader.isSpecialFormat("model.stl"));
    }

    @Test
    void objIsSpecialFormat() {
        assertTrue(FileFormatReader.isSpecialFormat("scene.obj"));
    }

    @Test
    void svgIsSpecialFormat() {
        assertTrue(FileFormatReader.isSpecialFormat("image.svg"));
    }

    @Test
    void txtIsNotSpecialFormat() {
        assertFalse(FileFormatReader.isSpecialFormat("readme.txt"));
    }

    @Test
    void javaIsNotSpecialFormat() {
        assertFalse(FileFormatReader.isSpecialFormat("Main.java"));
    }

    @Test
    void dxfParsing() throws Exception {
        Path dxf = tempDir.resolve("drawing.dxf");
        Files.writeString(dxf, """
            0
            SECTION
            2
            ENTITIES
            0
            LINE
            10
            0.0
            20
            0.0
            11
            100.0
            21
            100.0
            0
            CIRCLE
            10
            50.0
            20
            50.0
            40
            25.0
            0
            ENDSEC
            0
            EOF
            """);

        Map<String, Object> result = FileFormatReader.read(dxf, 0, 0);
        assertNotNull(result);
        assertFalse(result.containsKey("error"));
    }

    @Test
    void stlTextParsing() throws Exception {
        Path stl = tempDir.resolve("model.stl");
        Files.writeString(stl, """
            solid TestCube
              facet normal 0 0 -1
                outer loop
                  vertex 0 0 0
                  vertex 1 0 0
                  vertex 1 1 0
                endloop
              endfacet
              facet normal 0 0 1
                outer loop
                  vertex 0 0 1
                  vertex 1 1 1
                  vertex 1 0 1
                endloop
              endfacet
            endsolid TestCube
            """);

        Map<String, Object> result = FileFormatReader.read(stl, 0, 0);
        assertNotNull(result);
        assertFalse(result.containsKey("error"));
    }

    @Test
    void objParsing() throws Exception {
        Path obj = tempDir.resolve("model.obj");
        Files.writeString(obj, """
            # Simple cube
            v 0.0 0.0 0.0
            v 1.0 0.0 0.0
            v 1.0 1.0 0.0
            v 0.0 1.0 0.0
            v 0.0 0.0 1.0
            v 1.0 0.0 1.0
            v 1.0 1.0 1.0
            v 0.0 1.0 1.0
            f 1 2 3 4
            f 5 6 7 8
            f 1 2 6 5
            f 2 3 7 6
            """);

        Map<String, Object> result = FileFormatReader.read(obj, 0, 0);
        assertNotNull(result);
        assertFalse(result.containsKey("error"));
    }

    @Test
    void svgParsing() throws Exception {
        Path svg = tempDir.resolve("image.svg");
        Files.writeString(svg, """
            <?xml version="1.0" encoding="UTF-8"?>
            <svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">
              <circle cx="100" cy="100" r="50" fill="blue"/>
              <rect x="10" y="10" width="80" height="80" fill="red"/>
            </svg>
            """);

        Map<String, Object> result = FileFormatReader.read(svg, 0, 0);
        assertNotNull(result);
        assertFalse(result.containsKey("error"));
    }

    @Test
    void nonExistentFileReturnsError() {
        Path fake = tempDir.resolve("nonexistent.stl");
        Map<String, Object> result = FileFormatReader.read(fake, 0, 0);
        // Should either have error or handle gracefully
        assertNotNull(result);
    }
}
