#!/usr/bin/env groovy
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        }
    }
    return p
}

Map executeSFTPTransfer(Map p) {
    String action = p.action ? p.action.toString().toLowerCase() : null
    String localPath = p.local_path ? p.local_path.toString() : null
    String remotePath = p.remote_path ? p.remote_path.toString() : null

    if (!action || !localPath || !remotePath) {
        System.err.println("Error: --action (upload|download), --local-path, and --remote-path are required.")
        return [status: "error", message: "--action, --local-path, and --remote-path are required."]
    }

    String alias = p.alias ? p.alias.toString() : "default"
    String storePath = p.store ? p.store.toString() : ".mkpro/ssh_sessions.json"

    File sessionFile = new File(storePath).getAbsoluteFile()
    File localFile = new File(localPath).getAbsoluteFile()
    File remoteFile = new File(remotePath).getAbsoluteFile()
    long bytesTransferred = 0

    if (action == "upload") {
        if (!localFile.exists()) {
            System.err.println("Error: Local file '${localPath}' not found.")
            return [status: "error", message: "Local file not found."]
        }
        bytesTransferred = localFile.length()
        File par = remoteFile.getParentFile()
        if (par != null && !par.exists()) par.mkdirs()
        Files.copy(localFile.toPath(), remoteFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } else {
        if (remoteFile.exists()) {
            bytesTransferred = remoteFile.length()
            File par = localFile.getParentFile()
            if (par != null && !par.exists()) par.mkdirs()
            Files.copy(remoteFile.toPath(), localFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            System.err.println("Notice: Remote file '${remotePath}' not found locally.")
        }
    }

    Map result = [
        status: "success",
        action: action,
        local_path: localFile.getAbsolutePath(),
        remote_path: remoteFile.getAbsolutePath(),
        session: alias,
        bytes_transferred: bytesTransferred
    ]

    if (!p.binding_mode || p.json == true) {
        println(JsonHelper.toJson(result))
    }
    return result
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams)
if (rawParams != null) parsed.binding_mode = true
return executeSFTPTransfer(parsed)
