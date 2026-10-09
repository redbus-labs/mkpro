#!/usr/bin/env groovy
import java.io.File
import java.nio.file.Files

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
            if (pos == 0 && !p.pattern) p.pattern = t
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

void walkTree(File currentDir, File rootDir, Set<String> ignoredDirs, String pattern, String typeFilter, int maxResults, List results) {
    if (results.size() >= maxResults) return
    File[] files = currentDir.listFiles()
    if (files == null) return

    for (File f : files) {
        if (results.size() >= maxResults) break
        String name = f.getName()
        if (f.isDirectory()) {
            if (ignoredDirs.contains(name)) continue
            String relPath = rootDir.toPath().relativize(f.toPath()).toString().replace("\\", "/")
            if ((typeFilter == "all" || typeFilter == "dir") && matchesGlob(name, pattern)) {
                results.add([
                    name: name,
                    path: relPath,
                    type: "directory"
                ])
            }
            walkTree(f, rootDir, ignoredDirs, pattern, typeFilter, maxResults, results)
        } else if (f.isFile()) {
            if (typeFilter == "all" || typeFilter == "file") {
                if (matchesGlob(name, pattern)) {
                    String relPath = rootDir.toPath().relativize(f.toPath()).toString().replace("\\", "/")
                    results.add([
                        name: name,
                        path: relPath,
                        type: "file",
                        size_bytes: f.length()
                    ])
                }
            }
        }
    }
}

Map executeFindFiles(Map p) {
    String pattern = p.pattern ? p.pattern.toString() : (p.p ? p.p.toString() : null)
    if (!pattern) {
        System.err.println("Error: Pattern is required.")
        return [status: "error", message: "Pattern is required."]
    }

    String searchPath = p.path ? p.path.toString() : "."
    File rootDir = new File(searchPath).getAbsoluteFile()
    if (!rootDir.exists()) {
        System.err.println("Error: Path '${searchPath}' not found.")
        return [status: "error", message: "Path '${searchPath}' not found."]
    }

    String typeFilter = p.type ? p.type.toString().toLowerCase() : "file"
    int maxResults = p.max_results != null ? Integer.parseInt(p.max_results.toString()) : 100

    Set<String> ignoredDirs = [".git", "node_modules", "target", "build", ".gradle", ".idea", "venv", "__pycache__", ".mkpro"] as Set
    if (p.exclude) {
        p.exclude.toString().split(",").each { ignoredDirs.add(it.trim()) }
    }

    List results = []
    walkTree(rootDir, rootDir, ignoredDirs, pattern, typeFilter, maxResults, results)

    Map response = [
        total: results.size(),
        pattern: pattern,
        results: results
    ]

    if (!p.binding_mode || p.json == true) {
        println(JsonHelper.toJson(response))
    }
    return response
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams)
if (rawParams != null) parsed.binding_mode = true
return executeFindFiles(parsed)
