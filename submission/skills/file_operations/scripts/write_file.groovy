#!/usr/bin/env groovy
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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

Map executeWriteFile(Map p) {
    String filePath = p.path ? p.path.toString() : null
    if (!filePath) {
        System.err.println("Error: Target path is required.")
        return [status: "error", message: "Target path is required."]
    }

    String content = p.content != null ? p.content.toString() : ""
    String mode = p.mode ? p.mode.toString().toLowerCase() : "write"
    String encoding = p.encoding ? p.encoding.toString() : "UTF-8"

    File targetFile = new File(filePath).getAbsoluteFile()
    File parentDir = targetFile.getParentFile()
    if (parentDir != null && !parentDir.exists()) {
        parentDir.mkdirs()
    }

    byte[] bytes = content.getBytes(encoding)
    boolean atomic = false

    try {
        if (mode == "append") {
            Files.write(targetFile.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } else {
            // Atomic replacement via temp file in same directory
            File tempFile = new File(parentDir, "." + targetFile.getName() + "." + System.nanoTime() + ".tmp")
            Files.write(tempFile.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                atomic = true
            } catch (Exception moveEx) {
                // Fallback for filesystems not supporting atomic move
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                atomic = false
            }
        }

        Map res = [
            status: "success",
            path: targetFile.getAbsolutePath(),
            bytes_written: bytes.length,
            mode: mode,
            atomic: atomic
        ]

        if (!p.binding_mode || p.json == true) {
            println(JsonHelper.toJson(res))
        }
        return res
    } catch (Exception e) {
        System.err.println("Error writing to '${filePath}': ${e.message}")
        return [status: "error", message: e.message, path: targetFile.getAbsolutePath()]
    }
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams)
if (rawParams != null) parsed.binding_mode = true
return executeWriteFile(parsed)
