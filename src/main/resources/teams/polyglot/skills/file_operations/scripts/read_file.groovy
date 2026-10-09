#!/usr/bin/env groovy
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors

class JsonHelper {
    static String toJson(Object obj, int indent = 0) {
        String pad = "  " * indent
        String innerPad = "  " * (indent + 1)
        if (obj == null) return "null"
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString()
        if (obj instanceof CharSequence) {
            String s = obj.toString()
            s = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            return "\"" + s + "\""
        }
        if (obj instanceof Map) {
            Map m = (Map) obj
            if (m.isEmpty()) return "{}"
            List<String> entries = []
            m.each { k, v ->
                entries.add("${innerPad}\"${k}\": ${toJson(v, indent + 1)}")
            }
            return "{\n" + entries.join(",\n") + "\n${pad}}"
        }
        if (obj instanceof Collection || obj.getClass().isArray()) {
            List list = (obj instanceof Collection) ? (List) obj : Arrays.asList((Object[]) obj)
            if (list.isEmpty()) return "[]"
            List<String> items = []
            list.each { item ->
                items.add("${innerPad}${toJson(item, indent + 1)}")
            }
            return "[\n" + items.join(",\n") + "\n${pad}]"
        }
        return "\"" + obj.toString().replace("\"", "\\\"") + "\""
    }
}

Map parseArgs(Object rawArgs, Object rawParams) {
    Map p = [:]
    if (rawParams instanceof Map) {
        p.putAll((Map) rawParams)
    }
    List<String> tokens = []
    if (rawArgs instanceof String[]) {
        tokens = Arrays.asList((String[]) rawArgs)
    } else if (rawArgs instanceof List) {
        tokens = (List<String>) rawArgs
    } else if (rawArgs instanceof String && !rawArgs.trim().isEmpty()) {
        tokens = rawArgs.trim().split("\\s+").toList()
    }
    
    int pos = 0
    for (int i = 0; i < tokens.size(); i++) {
        String t = tokens[i]
        if (t.startsWith("--")) {
            String keyVal = t.substring(2)
            if (keyVal.contains("=")) {
                int eq = keyVal.indexOf("=")
                p[keyVal.substring(0, eq).replace('-', '_')] = keyVal.substring(eq + 1)
            } else {
                String k = keyVal.replace('-', '_')
                if (i + 1 < tokens.size() && !tokens[i + 1].startsWith("-")) {
                    p[k] = tokens[++i]
                } else {
                    p[k] = true
                }
            }
        } else if (t.startsWith("-") && t.length() > 1) {
            String k = t.substring(1).replace('-', '_')
            if (i + 1 < tokens.size() && !tokens[i + 1].startsWith("-")) {
                p[k] = tokens[++i]
            } else {
                p[k] = true
            }
        } else {
            if (pos == 0) p.path = t
            pos++
        }
    }
    return p
}

Map executeReadFile(Map p) {
    String filePath = p.path ? p.path.toString() : null
    if (!filePath) {
        System.err.println("Error: File path is required.")
        return [status: "error", message: "File path is required."]
    }

    File file = new File(filePath)
    if (!file.exists()) {
        System.err.println("Error: File '${filePath}' not found.")
        return [status: "error", message: "File '${filePath}' not found."]
    }
    if (file.isDirectory()) {
        System.err.println("Error: Path '${filePath}' is a directory, not a file.")
        return [status: "error", message: "Path '${filePath}' is a directory, not a file."]
    }

    String encoding = p.encoding ? p.encoding.toString() : "UTF-8"
    int startLine = 1
    if (p.start_line != null) startLine = Integer.parseInt(p.start_line.toString())
    else if (p.offset != null) startLine = Integer.parseInt(p.offset.toString())

    Integer endLine = null
    if (p.end_line != null) endLine = Integer.parseInt(p.end_line.toString())
    else if (p.limit != null) {
        int limit = Integer.parseInt(p.limit.toString())
        endLine = startLine + limit - 1
    }

    boolean lineNumbers = p.line_numbers == true || p.line_numbers?.toString()?.equalsIgnoreCase("true")
    boolean jsonOutput = p.json == true || p.json?.toString()?.equalsIgnoreCase("true")

    List<String> allLines = Files.readAllLines(file.toPath(), java.nio.charset.Charset.forName(encoding))
    int totalLines = allLines.size()
    long sizeBytes = file.length()

    int startIdx = Math.max(1, startLine) - 1
    int endIdx = endLine != null ? Math.min(totalLines, endLine) : totalLines
    if (startIdx > totalLines) startIdx = totalLines
    if (endIdx < startIdx) endIdx = startIdx

    List<String> sliced = (startIdx < totalLines && startIdx <= endIdx) ? allLines.subList(startIdx, endIdx) : []
    String content = sliced.join("\n")

    if (!jsonOutput && !p.binding_mode) {
        for (int i = 0; i < sliced.size(); i++) {
            int lineNum = startIdx + i + 1
            if (lineNumbers) {
                System.out.printf("%6d | %s%n", lineNum, sliced[i])
            } else {
                System.out.println(sliced[i])
            }
        }
    }

    Map result = [
        status: "success",
        path: file.getAbsolutePath(),
        total_lines: totalLines,
        size_bytes: sizeBytes,
        start_line: startIdx + 1,
        end_line: endIdx,
        lines_read: sliced.size(),
        content: content,
        lines: sliced
    ]

    if (jsonOutput) {
        println(JsonHelper.toJson(result))
    }
    return result
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams)
if (rawParams != null) parsed.binding_mode = true
return executeReadFile(parsed)
