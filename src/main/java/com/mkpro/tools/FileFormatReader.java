package com.mkpro.tools;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Extracts text content from various file formats.
 * Auto-detected by file extension in read_file tool.
 *
 * Supported: PDF, DOCX, XLSX, PPTX, SVG, DXF, STL, OBJ
 */
public class FileFormatReader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "pdf", "docx", "xlsx", "pptx", "svg", "dxf", "stl", "obj"
    );

    /**
     * Check if a file path has a supported binary/special format extension.
     */
    public static boolean isSpecialFormat(String path) {
        String ext = getExtension(path);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * Read a special format file and return structured result.
     */
    public static Map<String, Object> read(Path filePath, int startPage, int endPage) {
        String ext = getExtension(filePath.toString());
        try {
            return switch (ext) {
                case "pdf" -> readPdf(filePath, startPage, endPage);
                case "docx" -> readDocx(filePath);
                case "xlsx" -> readXlsx(filePath);
                case "pptx" -> readPptx(filePath);
                case "svg" -> readSvg(filePath);
                case "dxf" -> readDxf(filePath);
                case "stl" -> readStl(filePath);
                case "obj" -> readObj(filePath);
                default -> Map.of("error", "Unsupported format: " + ext);
            };
        } catch (Exception e) {
            return Map.of("error", "Failed to read " + ext.toUpperCase() + ": " + e.getMessage());
        }
    }

    // ========== PDF ==========
    private static Map<String, Object> readPdf(Path path, int startPage, int endPage) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(path.toFile())) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            int totalPages = document.getNumberOfPages();

            int sp = Math.max(1, startPage);
            int ep = endPage > 0 ? Math.min(endPage, totalPages) : Math.min(sp + 9, totalPages);

            StringBuilder fullText = new StringBuilder();
            List<String> renderedImages = new ArrayList<>();

            // Process each page — if text is sparse, render as image for vision
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);

            for (int page = sp; page <= ep; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document).trim();

                if (pageText.length() >= 50) {
                    // Sufficient text — use extracted content
                    fullText.append("--- Page ").append(page).append(" ---\n");
                    fullText.append(pageText).append("\n\n");
                } else {
                    // Sparse/no text — render page as image for vision analysis
                    try {
                        java.awt.image.BufferedImage image = renderer.renderImageWithDPI(page - 1, 150);
                        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("mkpro-pdf-");
                        java.nio.file.Path imgPath = tempDir.resolve("page_" + page + ".png");
                        javax.imageio.ImageIO.write(image, "PNG", imgPath.toFile());

                        renderedImages.add(imgPath.toAbsolutePath().toString());
                        fullText.append("--- Page ").append(page).append(" ---\n");
                        fullText.append("[IMAGE-BASED PAGE] Text extraction returned minimal content. ");
                        fullText.append("Page rendered to: ").append(imgPath.toAbsolutePath()).append("\n");
                        fullText.append("Use the vision/image tool to analyze this page visually.\n\n");
                    } catch (Exception renderErr) {
                        fullText.append("--- Page ").append(page).append(" ---\n");
                        fullText.append("[IMAGE-BASED PAGE] Could not render: ").append(renderErr.getMessage()).append("\n\n");
                    }
                }
            }

            String text = fullText.toString();
            if (text.length() > 50000) {
                text = text.substring(0, 50000) + "\n... [truncated at 50KB]";
            }

            Map<String, Object> result = new HashMap<>();
            result.put("content", text);
            result.put("format", "pdf");
            result.put("total_pages", totalPages);
            result.put("showing_pages", sp + "-" + ep);
            if (!renderedImages.isEmpty()) {
                result.put("rendered_images", renderedImages);
                result.put("note", renderedImages.size() + " page(s) are image-based and were rendered as PNG. Use the vision tool to read them.");
            }
            if (ep < totalPages) {
                result.put("has_more", true);
                result.put("next_start_line", ep + 1);
            }
            return result;
        }
    }

    // ========== DOCX ==========
    private static Map<String, Object> readDocx(Path path) throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = 
                new org.apache.poi.xwpf.usermodel.XWPFDocument(Files.newInputStream(path))) {
            
            StringBuilder sb = new StringBuilder();
            int paraCount = 0;

            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                    paraCount++;
                }
            }

            // Also extract tables
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
                sb.append("\n[TABLE]\n");
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText());
                    }
                    sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
                }
            }

            String content = sb.toString();
            if (content.length() > 50000) {
                content = content.substring(0, 50000) + "\n... [truncated at 50KB]";
            }

            Map<String, Object> result = new HashMap<>();
            result.put("content", content);
            result.put("format", "docx");
            result.put("paragraphs", paraCount);
            result.put("tables", doc.getTables().size());
            return result;
        }
    }

    // ========== XLSX ==========
    private static Map<String, Object> readXlsx(Path path) throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = 
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(path))) {
            
            StringBuilder sb = new StringBuilder();
            int sheetCount = workbook.getNumberOfSheets();

            for (int i = 0; i < sheetCount; i++) {
                org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.getSheetAt(i);
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");

                int rowCount = 0;
                for (org.apache.poi.ss.usermodel.Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (org.apache.poi.ss.usermodel.Cell cell : row) {
                        cells.add(getCellValue(cell));
                    }
                    sb.append(String.join("\t", cells)).append("\n");
                    rowCount++;
                    if (rowCount > 500) {
                        sb.append("... [truncated at 500 rows]\n");
                        break;
                    }
                }
                sb.append("\n");
            }

            String content = sb.toString();
            if (content.length() > 50000) {
                content = content.substring(0, 50000) + "\n... [truncated at 50KB]";
            }

            Map<String, Object> result = new HashMap<>();
            result.put("content", content);
            result.put("format", "xlsx");
            result.put("sheets", sheetCount);
            return result;
        }
    }

    private static String getCellValue(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double v = cell.getNumericCellValue();
                yield (v == Math.floor(v)) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield String.valueOf(cell.getNumericCellValue()); }
                catch (Exception e) { yield cell.getStringCellValue(); }
            }
            default -> "";
        };
    }

    // ========== PPTX ==========
    private static Map<String, Object> readPptx(Path path) throws Exception {
        try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt = 
                new org.apache.poi.xslf.usermodel.XMLSlideShow(Files.newInputStream(path))) {
            
            StringBuilder sb = new StringBuilder();
            int slideNum = 0;

            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : ppt.getSlides()) {
                slideNum++;
                sb.append("--- Slide ").append(slideNum).append(" ---\n");

                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }

            String content = sb.toString();
            Map<String, Object> result = new HashMap<>();
            result.put("content", content);
            result.put("format", "pptx");
            result.put("total_slides", slideNum);
            return result;
        }
    }

    // ========== SVG (XML text extraction) ==========
    private static Map<String, Object> readSvg(Path path) throws Exception {
        String raw = Files.readString(path);

        StringBuilder sb = new StringBuilder();

        // Extract text elements
        java.util.regex.Pattern textPattern = java.util.regex.Pattern.compile("<text[^>]*>(.*?)</text>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = textPattern.matcher(raw);
        List<String> texts = new ArrayList<>();
        while (m.find()) {
            String text = m.group(1).replaceAll("<[^>]+>", "").trim();
            if (!text.isEmpty()) texts.add(text);
        }

        // Extract dimensions from root <svg> element
        java.util.regex.Pattern svgPattern = java.util.regex.Pattern.compile("<svg[^>]*(?:width=\"([^\"]+)\")?[^>]*(?:height=\"([^\"]+)\")?[^>]*(?:viewBox=\"([^\"]+)\")?");
        java.util.regex.Matcher svgM = svgPattern.matcher(raw);
        String dimensions = "";
        if (svgM.find()) {
            dimensions = "width=" + (svgM.group(1) != null ? svgM.group(1) : "?") + 
                         " height=" + (svgM.group(2) != null ? svgM.group(2) : "?") +
                         " viewBox=" + (svgM.group(3) != null ? svgM.group(3) : "?");
        }

        sb.append("SVG Dimensions: ").append(dimensions).append("\n");
        sb.append("Text elements (").append(texts.size()).append("):\n");
        for (String t : texts) {
            sb.append("  • ").append(t).append("\n");
        }

        // Count shapes
        int paths = countOccurrences(raw, "<path");
        int rects = countOccurrences(raw, "<rect");
        int circles = countOccurrences(raw, "<circle");
        int lines = countOccurrences(raw, "<line");
        sb.append("\nShapes: ").append(paths).append(" paths, ").append(rects).append(" rects, ")
          .append(circles).append(" circles, ").append(lines).append(" lines\n");

        Map<String, Object> result = new HashMap<>();
        result.put("content", sb.toString());
        result.put("format", "svg");
        result.put("text_elements", texts.size());
        result.put("file_size_bytes", raw.length());
        return result;
    }

    // ========== DXF (AutoCAD text/dimension extraction) ==========
    private static Map<String, Object> readDxf(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path);
        
        StringBuilder sb = new StringBuilder();
        List<String> textEntities = new ArrayList<>();
        List<String> dimensions = new ArrayList<>();
        List<String> layers = new LinkedHashSet<String>() {}.stream().collect(java.util.stream.Collectors.toList());
        Set<String> layerSet = new LinkedHashSet<>();

        // Simple DXF parser: look for TEXT, MTEXT, DIMENSION entities and LAYER names
        for (int i = 0; i < lines.size() - 1; i++) {
            String code = lines.get(i).trim();
            String value = (i + 1 < lines.size()) ? lines.get(i + 1).trim() : "";

            // Layer names (group code 8)
            if ("8".equals(code) && !value.isEmpty()) {
                layerSet.add(value);
            }

            // TEXT and MTEXT content (group code 1)
            if ("1".equals(code) && !value.isEmpty()) {
                // Check if we're in a TEXT/MTEXT/DIMENSION section
                // Look back for entity type
                for (int j = Math.max(0, i - 20); j < i; j++) {
                    String prev = lines.get(j).trim();
                    if ("TEXT".equals(prev) || "MTEXT".equals(prev)) {
                        textEntities.add(value);
                        break;
                    } else if ("DIMENSION".equals(prev)) {
                        dimensions.add(value);
                        break;
                    }
                }
            }
        }

        sb.append("DXF File Analysis:\n");
        sb.append("  Layers (").append(layerSet.size()).append("): ").append(String.join(", ", layerSet)).append("\n\n");
        
        sb.append("  Text Annotations (").append(textEntities.size()).append("):\n");
        for (String t : textEntities) {
            sb.append("    • ").append(t).append("\n");
        }
        
        sb.append("\n  Dimensions (").append(dimensions.size()).append("):\n");
        for (String d : dimensions) {
            sb.append("    • ").append(d).append("\n");
        }

        sb.append("\n  Total lines in file: ").append(lines.size());

        Map<String, Object> result = new HashMap<>();
        result.put("content", sb.toString());
        result.put("format", "dxf");
        result.put("layers", layerSet.size());
        result.put("text_annotations", textEntities.size());
        result.put("dimensions", dimensions.size());
        return result;
    }

    // ========== STL (3D mesh metadata) ==========
    private static Map<String, Object> readStl(Path path) throws Exception {
        byte[] header = new byte[80];
        boolean isAscii;
        
        try (InputStream is = Files.newInputStream(path)) {
            is.read(header);
            isAscii = new String(header).trim().startsWith("solid");
        }

        Map<String, Object> result = new HashMap<>();
        long fileSize = Files.size(path);
        result.put("format", "stl");
        result.put("file_size_bytes", fileSize);

        if (isAscii) {
            // ASCII STL
            List<String> lines = Files.readAllLines(path);
            String name = lines.get(0).replace("solid", "").trim();
            int facetCount = (int) lines.stream().filter(l -> l.trim().startsWith("facet normal")).count();
            
            // Compute bounding box from vertices
            double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("vertex")) {
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length >= 4) {
                        for (int i = 0; i < 3; i++) {
                            double v = Double.parseDouble(parts[i + 1]);
                            min[i] = Math.min(min[i], v);
                            max[i] = Math.max(max[i], v);
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("STL 3D Mesh (ASCII):\n");
            sb.append("  Name: ").append(name.isEmpty() ? "(unnamed)" : name).append("\n");
            sb.append("  Facets (triangles): ").append(facetCount).append("\n");
            sb.append("  Vertices: ~").append(facetCount * 3).append("\n");
            if (min[0] != Double.MAX_VALUE) {
                sb.append(String.format("  Bounding Box: X[%.2f, %.2f] Y[%.2f, %.2f] Z[%.2f, %.2f]\n",
                    min[0], max[0], min[1], max[1], min[2], max[2]));
                sb.append(String.format("  Dimensions: %.2f x %.2f x %.2f\n",
                    max[0]-min[0], max[1]-min[1], max[2]-min[2]));
            }

            result.put("content", sb.toString());
            result.put("type", "ascii");
            result.put("facets", facetCount);
        } else {
            // Binary STL
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                dis.skip(80); // header
                int facetCount = Integer.reverseBytes(dis.readInt());

                StringBuilder sb = new StringBuilder();
                sb.append("STL 3D Mesh (Binary):\n");
                sb.append("  Facets (triangles): ").append(facetCount).append("\n");
                sb.append("  Vertices: ~").append(facetCount * 3).append("\n");
                sb.append("  File size: ").append(fileSize / 1024).append(" KB\n");

                result.put("content", sb.toString());
                result.put("type", "binary");
                result.put("facets", facetCount);
            }
        }

        return result;
    }

    // ========== OBJ (Wavefront 3D) ==========
    private static Map<String, Object> readObj(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path);
        
        int vertices = 0, faces = 0, normals = 0, texCoords = 0;
        List<String> materials = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("v ")) {
                vertices++;
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 4) {
                    for (int i = 0; i < 3; i++) {
                        try {
                            double v = Double.parseDouble(parts[i + 1]);
                            min[i] = Math.min(min[i], v);
                            max[i] = Math.max(max[i], v);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } else if (trimmed.startsWith("f ")) {
                faces++;
            } else if (trimmed.startsWith("vn ")) {
                normals++;
            } else if (trimmed.startsWith("vt ")) {
                texCoords++;
            } else if (trimmed.startsWith("mtllib ")) {
                materials.add(trimmed.substring(7).trim());
            } else if (trimmed.startsWith("g ")) {
                groups.add(trimmed.substring(2).trim());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("OBJ 3D Model:\n");
        sb.append("  Vertices: ").append(vertices).append("\n");
        sb.append("  Faces: ").append(faces).append("\n");
        sb.append("  Normals: ").append(normals).append("\n");
        sb.append("  Texture coords: ").append(texCoords).append("\n");
        if (!materials.isEmpty()) sb.append("  Materials: ").append(String.join(", ", materials)).append("\n");
        if (!groups.isEmpty()) sb.append("  Groups: ").append(String.join(", ", groups)).append("\n");
        if (min[0] != Double.MAX_VALUE) {
            sb.append(String.format("  Bounding Box: X[%.2f, %.2f] Y[%.2f, %.2f] Z[%.2f, %.2f]\n",
                min[0], max[0], min[1], max[1], min[2], max[2]));
            sb.append(String.format("  Dimensions: %.2f x %.2f x %.2f\n",
                max[0]-min[0], max[1]-min[1], max[2]-min[2]));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("content", sb.toString());
        result.put("format", "obj");
        result.put("vertices", vertices);
        result.put("faces", faces);
        return result;
    }

    // ========== Helpers ==========
    private static String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
    }

    private static int countOccurrences(String text, String target) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) { count++; idx += target.length(); }
        return count;
    }
}
