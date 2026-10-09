#!/usr/bin/env groovy
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

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
        if (t == "-r" || t == "--recursive") {
            p.recursive = true
        } else if (t.startsWith("--")) {
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

boolean matchesGlob(String filename, String pattern) {
    if (pattern == null || pattern == "*") return true
    String regex = pattern
        .replace(".", "\\.")
        .replace("*", ".*")
        .replace("?", ".")
    return filename.matches(regex)
}

void collectEntries(File currentDir, File baseDir, int currentDepth, int maxDepth, boolean recursive, String pattern, boolean includeHidden, List results) {
    if (!currentDir.exists() || !currentDir.isDirectory()) return
    
    File[] files = currentDir.listFiles()
    if (files == null) return
    
    List<File> sortedFiles = files.toList().sort { a, b ->
        if (a.isDirectory() != b.isDirectory()) {
            return a.isDirectory() ? -1 : 1
        }
        return a.getName().compareToIgnoreCase(b.getName())
    }

    for (File f : sortedFiles) {
        boolean isHidden = f.isHidden() || f.getName().startsWith(".")
        if (isHidden && !includeHidden) {
            continue
        }
        
        String relPath = baseDir.toPath().relativize(f.toPath()).toString().replace("\\", "/")
        
        if (f.isDirectory()) {
            results.add([
                name: f.getName(),
                path: relPath,
                type: "directory",
                hidden: isHidden
            ])
            if (recursive && currentDepth < maxDepth) {
                collectEntries(f, baseDir, currentDepth + 1, maxDepth, recursive, pattern, includeHidden, results)
            }
        } else {
            if (matchesGlob(f.getName(), pattern)) {
                results.add([
                    name: f.getName(),
                    path: relPath,
                    type: "file",
                    size_bytes: f.length(),
                    hidden: isHidden
                ])
            }
        }
    }
}

Map executeListDirectory(Map p) {
    String dirPath = p.path ? p.path.toString() : "."
    File targetDir = new File(dirPath).getAbsoluteFile()

    if (!targetDir.exists()) {
        System.err.println("Error: Path '${dirPath}' not found.")
        return [status: "error", message: "Path '${dirPath}' not found."]
    }
    if (!targetDir.isDirectory()) {
        System.err.println("Error: Path '${dirPath}' is not a directory.")
        return [status: "error", message: "Path '${dirPath}' is not a directory."]
    }

    boolean recursive = p.recursive == true || p.recursive?.toString()?.equalsIgnoreCase("true")
    int depth = p.depth != null ? Integer.parseInt(p.depth.toString()) : 3
    String pattern = p.pattern ? p.pattern.toString() : "*"
    boolean includeHidden = p.include_hidden == true || p.include_hidden?.toString()?.equalsIgnoreCase("true")
    boolean jsonOutput = p.json == true || p.json?.toString()?.equalsIgnoreCase("true")

    List results = []
    collectEntries(targetDir, targetDir, 1, depth, recursive, pattern, includeHidden, results)

    Map response = [
        directory: targetDir.getAbsolutePath(),
        total_entries: results.size(),
        entries: results
    ]

    if (jsonOutput || p.binding_mode) {
        if (jsonOutput) println(JsonHelper.toJson(response))
    } else {
        for (Map item : results) {
            String prefix = item.type == "directory" ? "[DIR]     " : String.format("[%8d B] ", item.size_bytes)
            println("${prefix}${item.path}")
        }
    }
    return response
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams)
if (rawParams != null) parsed.binding_mode = true
return executeListDirectory(parsed)
