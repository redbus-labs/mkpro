#!/usr/bin/env groovy
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.time.Instant
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
            s = s.replace("\\", "\\\\").replace(""", "\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            return """ + s + """
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
        return "\"" + obj.toString().replace(""", "\\"") + "\""
    }
}

boolean isDirectBinding(Object b) {
    if (b == null) return false
    try {
        def vars = b.hasProperty("variables") ? b.variables : null
        if (vars instanceof Map) {
            return vars.any { k, v -> k != "args" && k != "params" && !k.startsWith("_") }
        }
    } catch (Exception ignored) {}
    return false
}

Map parseSessionsJson(String json) {
    Map sessions = [:]
    if (!json || json.trim().isEmpty()) return sessions
    Pattern p = Pattern.compile('"([^"]+)"\\s*:\\s*\\{([^{}]+)\\}')
    Matcher m = p.matcher(json)
    while (m.find()) {
        String aliasName = m.group(1)
        String block = m.group(2)
        Map session = [:]
        Pattern fieldPattern = Pattern.compile('"([^"]+)"\\s*:\\s*("(?:[^"\\\\]|\\\\.)*"|[^,}\\r\\n]+)')
        Matcher fm = fieldPattern.matcher(block)
        while (fm.find()) {
            String fKey = fm.group(1)
            String fVal = fm.group(2).trim()
            if (fVal.startsWith(""") && fVal.endsWith(""") && fVal.length() >= 2) {
                fVal = fVal.substring(1, fVal.length() - 1)
                fVal = fVal.replace("\\"", """).replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                session[fKey] = fVal
            } else if (fVal == "true" || fVal == "false") {
                session[fKey] = Boolean.parseBoolean(fVal)
            } else {
                try {
                    session[fKey] = Integer.parseInt(fVal)
                } catch (Exception ignored) {
                    session[fKey] = fVal
                }
            }
        }
        if (!session.isEmpty()) {
            sessions[aliasName] = session
        }
    }
    return sessions
}

Map parseArgs(Object rawArgs, Object rawParams, Object bindingObj = null) {
    Map p = [:]
    if (rawParams instanceof Map) {
        p.putAll((Map) rawParams)
    }
    if (bindingObj != null) {
        try {
            def vars = bindingObj.hasProperty("variables") ? bindingObj.variables : null
            if (vars instanceof Map) {
                vars.each { k, v ->
                    if (k != "args" && k != "params" && !k.startsWith("_") && !p.containsKey(k)) {
                        p[k] = v
                    }
                }
            }
        } catch (Exception ignored) {}
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
        if (t == "--json") {
            p.json = true
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
        }
    }
    return p
}

Map executeSSHConnect(Map p) {
    String host = p.host ? p.host.toString() : null
    String username = p.username ? p.username.toString() : (p.user ? p.user.toString() : null)

    if (!host || !username) {
        System.err.println("Error: Both host and username are required.")
        return [status: "error", message: "Both host and username are required."]
    }

    int port = p.port != null ? Integer.parseInt(p.port.toString()) : 22
    String alias = p.alias ? p.alias.toString() : "default"
    String keyPath = p.key_path ? p.key_path.toString() : null
    String keyContent = p.key_content ? p.key_content.toString() : null
    String password = p.password ? p.password.toString() : null
    String storePath = p.store ? p.store.toString() : ".mkpro/ssh_sessions.json"

    File sessionFile = new File(storePath).getAbsoluteFile()
    File parentDir = sessionFile.getParentFile()
    if (parentDir != null && !parentDir.exists()) {
        parentDir.mkdirs()
    }

    String authType = keyPath != null ? "KEY_FILE" : (keyContent != null ? "KEY_CONTENT" : "PASSWORD")

    Map sessions = [:]
    if (sessionFile.exists()) {
        try {
            String content = new String(Files.readAllBytes(sessionFile.toPath()), "UTF-8")
            sessions = parseSessionsJson(content)
        } catch (Exception ignored) {}
    }

    String nowIso = Instant.now().toString()
    sessions[alias] = [
        alias: alias,
        host: host,
        username: username,
        port: port,
        auth_type: authType,
        key_path: keyPath,
        has_password: password != null,
        connected_at: nowIso,
        status: "ACTIVE"
    ]

    String jsonOut = JsonHelper.toJson(sessions)
    File tmp = new File(parentDir, ".ssh_sessions." + System.nanoTime() + ".tmp")
    Files.write(tmp.toPath(), jsonOut.getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    try {
        Files.move(tmp.toPath(), sessionFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (Exception ex) {
        Files.move(tmp.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    Map res = [
        status: "connected",
        alias: alias,
        host: host,
        port: port,
        user: username,
        auth_type: authType,
        session_store: sessionFile.getAbsolutePath()
    ]

    if (!p.binding_mode || p.json == true) {
        println(JsonHelper.toJson(res))
    }
    return res
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams, binding)
if (rawParams != null || isDirectBinding(binding)) parsed.binding_mode = true
return executeSSHConnect(parsed)
