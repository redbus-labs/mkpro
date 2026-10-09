#!/usr/bin/env groovy
import java.io.File
import java.nio.file.Files
import java.util.regex.Pattern
import java.util.regex.Matcher

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
            if (pos == 0 && !p.query) p.query = t
            pos++
        }
    }
    return p
}

List<File> collectFiles(File dir, Set<String> ignoredDirs, List<String> fileTypes) {
    List<File> results = []
    File[] list = dir.listFiles()
    if (list == null) return results
    
    for (File f : list) {
        if (f.isDirectory()) {
            if (!ignoredDirs.contains(f.getName())) {
                results.addAll(collectFiles(f, ignoredDirs, fileTypes))
            }
        } else if (f.isFile()) {
            if (fileTypes != null && !fileTypes.isEmpty()) {
                String name = f.getName().toLowerCase()
                boolean match = fileTypes.any { ext -> name.endsWith(ext) }
                if (!match) continue
            }
            results.add(f)
        }
    }
    return results
}

Map executeSearchCodebase(Map p) {
    String query = p.query ? p.query.toString() : null
    if (!query) {
        System.err.println("Error: Search query is required.")
        return [status: "error", message: "Search query is required."]
    }

    String searchPath = p.path ? p.path.toString() : "."
    File rootDir = new File(searchPath).getAbsoluteFile()
    if (!rootDir.exists()) {
        System.err.println("Error: Search path '${searchPath}' not found.")
        return [status: "error", message: "Search path '${searchPath}' not found."]
    }

    boolean isRegex = p.regex == true || p.regex?.toString()?.equalsIgnoreCase("true")
    boolean caseSensitive = p.case_sensitive == true || p.case_sensitive?.toString()?.equalsIgnoreCase("true")
    int maxResults = p.max_results != null ? Integer.parseInt(p.max_results.toString()) : 50
    int contextLines = (p.context != null) ? Integer.parseInt(p.context.toString()) : (p.C != null ? Integer.parseInt(p.C.toString()) : 0)
    boolean jsonOutput = p.json == true || p.json?.toString()?.equalsIgnoreCase("true")

    List<String> fileTypes = []
    if (p.file_types) {
        fileTypes = p.file_types.toString().split(",").collect { it.trim().toLowerCase() }
    }

    Set<String> ignoredDirs = [".git", "node_modules", "target", "build", ".gradle", ".idea", "venv", "__pycache__", ".mkpro"] as Set

    int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE
    Pattern pattern = isRegex ? Pattern.compile(query, flags) : Pattern.compile(Pattern.quote(query), flags)

    List<File> targetFiles = collectFiles(rootDir, ignoredDirs, fileTypes)
    List matches = []
    int totalCount = 0

    for (File f : targetFiles) {
        if (totalCount >= maxResults) break
        String relPath = rootDir.toPath().relativize(f.toPath()).toString().replace("\\", "/")

        try {
            List<String> lines = Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)
            for (int lineNo = 1; lineNo <= lines.size(); lineNo++) {
                String line = lines[lineNo - 1]
                if (pattern.matcher(line).find()) {
                    Map matchItem = [
                        file: relPath,
                        line: lineNo,
                        content: line
                    ]

                    if (contextLines > 0) {
                        int beforeStart = Math.max(0, lineNo - 1 - contextLines)
                        int beforeEnd = lineNo - 1
                        List<String> ctxBefore = (beforeStart < beforeEnd) ? lines.subList(beforeStart, beforeEnd) : []

                        int afterStart = lineNo
                        int afterEnd = Math.min(lines.size(), lineNo + contextLines)
                        List<String> ctxAfter = (afterStart < afterEnd) ? lines.subList(afterStart, afterEnd) : []

                        matchItem.context_before = ctxBefore
                        matchItem.context_after = ctxAfter
                    }

                    matches.add(matchItem)
                    totalCount++
                    if (totalCount >= maxResults) break
                }
            }
        } catch (Exception ignored) {}
    }

    Map response = [
        query: query,
        total: matches.size(),
        matches: matches
    ]

    if (jsonOutput || p.binding_mode) {
        if (jsonOutput) println(JsonHelper.toJson(response))
    } else {
        for (Map m : matches) {
            println("${m.file}:${m.line}: ${m.content}")
        }
    }
    return response
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams)
if (rawParams != null) parsed.binding_mode = true
return executeSearchCodebase(parsed)
