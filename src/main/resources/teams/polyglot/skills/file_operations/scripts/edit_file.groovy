#!/usr/bin/env groovy
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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

class DiffGenerator {
    static String generateUnifiedDiff(String filePath, String original, String modified) {
        List<String> origLines = original.split("\r?\n", -1) as List
        List<String> modLines = modified.split("\r?\n", -1) as List
        
        StringBuilder sb = new StringBuilder()
        sb.append("--- a/").append(filePath).append("\n")
        sb.append("+++ b/").append(filePath).append("\n")
        
        int origLen = origLines.size()
        int modLen = modLines.size()
        
        // Simple unified diff calculation
        sb.append("@@ -1,").append(origLen).append(" +1,").append(modLen).append(" @@\n")
        
        int i = 0, j = 0
        while (i < origLen || j < modLen) {
            if (i < origLen && j < modLen && origLines[i] == modLines[j]) {
                sb.append(" ").append(origLines[i]).append("\n")
                i++
                j++
            } else {
                if (i < origLen && (j >= modLen || origLines[i] != modLines[j])) {
                    sb.append("-").append(origLines[i]).append("\n")
                    i++
                }
                if (j < modLen && (i > origLen || j < modLen)) {
                    sb.append("+").append(modLines[j]).append("\n")
                    j++
                }
            }
        }
        return sb.toString()
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

Map executeEditFile(Map p) {
    String filePath = p.path ? p.path.toString() : null
    if (!filePath) {
        System.err.println("Error: File path is required.")
        return [status: "error", message: "File path is required."]
    }

    File file = new File(filePath).getAbsoluteFile()
    if (!file.exists()) {
        System.err.println("Error: File '${filePath}' not found.")
        return [status: "error", message: "File '${filePath}' not found."]
    }

    String target = p.target != null ? p.target.toString() : null
    String replacement = p.replacement != null ? p.replacement.toString() : ""
    boolean isRegex = p.regex == true || p.regex?.toString()?.equalsIgnoreCase("true")
    boolean diffOnly = p.diff == true || p.diff?.toString()?.equalsIgnoreCase("true")
    int count = p.count != null ? Integer.parseInt(p.count.toString()) : 1

    if (target == null) {
        System.err.println("Error: --target is required.")
        return [status: "error", message: "--target is required."]
    }

    String original = new String(Files.readAllBytes(file.toPath()), "UTF-8")
    String modified = original
    int replacementsDone = 0

    if (isRegex) {
        Pattern pattern = Pattern.compile(target)
        Matcher matcher = pattern.matcher(original)
        StringBuffer sb = new StringBuffer()
        while (matcher.find()) {
            if (count <= 0 || replacementsDone < count) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
                replacementsDone++
            } else {
                break
            }
        }
        matcher.appendTail(sb)
        modified = sb.toString()
    } else {
        if (!original.contains(target)) {
            System.err.println("Error: Target text not found in '${filePath}'.")
            return [status: "error", message: "Target text not found in file.", path: file.getAbsolutePath()]
        }
        if (count == 0) {
            modified = original.replace(target, replacement)
            // estimate count
            replacementsDone = (original.length() - original.replace(target, "").length()) / target.length()
        } else {
            int curIdx = 0
            StringBuilder sb = new StringBuilder()
            int lastIdx = 0
            while (replacementsDone < count && (curIdx = original.indexOf(target, lastIdx)) != -1) {
                sb.append(original.substring(lastIdx, curIdx))
                sb.append(replacement)
                lastIdx = curIdx + target.length()
                replacementsDone++
            }
            sb.append(original.substring(lastIdx))
            modified = sb.toString()
        }
    }

    String diffText = DiffGenerator.generateUnifiedDiff(filePath, original, modified)

    if (diffOnly) {
        if (!p.binding_mode) {
            println(diffText.isEmpty() ? "No changes detected." : diffText)
        }
        return [
            status: "preview",
            path: file.getAbsolutePath(),
            replacements: replacementsDone,
            diff: diffText,
            written: false
        ]
    }

    // Atomic write
    File parentDir = file.getParentFile()
    File tempFile = new File(parentDir, "." + file.getName() + "." + System.nanoTime() + ".tmp")
    Files.write(tempFile.toPath(), modified.getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    try {
        Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (Exception ex) {
        Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    Map res = [
        status: "success",
        path: file.getAbsolutePath(),
        replacements: replacementsDone,
        diff: diffText,
        written: true
    ]

    if (!p.binding_mode || p.json == true) {
        println(JsonHelper.toJson(res))
    }
    return res
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams)
if (rawParams != null) parsed.binding_mode = true
return executeEditFile(parsed)
