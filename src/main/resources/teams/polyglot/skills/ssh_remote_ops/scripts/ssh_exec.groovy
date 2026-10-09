#!/usr/bin/env groovy
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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
        if (t == "-c" || t == "--command") {
            if (i + 1 < tokens.size()) p.command = tokens[++i]
        } else if (t == "--json") {
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
        } else {
            if (pos == 0 && !p.command) p.command = t
            pos++
        }
    }
    return p
}

Map executeSSHCmd(Map p) {
    String cmdStr = p.command ? p.command.toString() : (p.c ? p.c.toString() : null)
    if (!cmdStr) {
        System.err.println("Error: Command is required.")
        return [status: "error", message: "Command is required."]
    }

    String alias = p.alias ? p.alias.toString() : "default"
    int timeoutSec = p.timeout != null ? Integer.parseInt(p.timeout.toString()) : 30
    String storePath = p.store ? p.store.toString() : ".mkpro/ssh_sessions.json"

    File sessionFile = new File(storePath).getAbsoluteFile()
    if (!sessionFile.exists()) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win")
        List<String> fallbackCmd = isWindows ? ["cmd.exe", "/c", cmdStr] : ["/bin/sh", "-c", cmdStr]
        ProcessBuilder pb = new ProcessBuilder(fallbackCmd)
        try {
            Process proc = pb.start()
            StringBuilder stdoutBuf = new StringBuilder()
            StringBuilder stderrBuf = new StringBuilder()
            Thread th1 = Thread.startDaemon {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"))
                    String l; while ((l = r.readLine()) != null) stdoutBuf.append(l).append('\n')
                } catch (Exception ignored) {}
            }
            Thread th2 = Thread.startDaemon {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), "UTF-8"))
                    String l; while ((l = r.readLine()) != null) stderrBuf.append(l).append('\n')
                } catch (Exception ignored) {}
            }
            proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            th1.join(500)
            th2.join(500)

            Map res = [
                status: proc.exitValue() == 0 ? "fallback" : "error",
                session: alias,
                command: cmdStr,
                returncode: proc.exitValue(),
                stdout: stdoutBuf.toString().trim(),
                stderr: stderrBuf.toString().trim()
            ]

            if (!p.binding_mode || p.json == true) println(JsonHelper.toJson(res))
            return res
        } catch (Exception e) {
            return [status: "error", message: e.message]
        }
    }

    Map sessions = [:]
    try {
        String content = new String(Files.readAllBytes(sessionFile.toPath()), "UTF-8")
        sessions = parseSessionsJson(content)
    } catch (Exception ex) {
        return [status: "error", message: "Failed to read session store: " + ex.message]
    }

    Map session = sessions[alias]
    if (!session) {
        System.err.println("Error: SSH session alias '" + alias + "' not found.")
        return [status: "error", message: "SSH session alias '" + alias + "' not found."]
    }

    String host = session.host
    String user = session.username
    int port = session.port != null ? Integer.parseInt(session.port.toString()) : 22
    String keyPath = session.key_path

    List<String> sshCmd = ["ssh", "-p", String.valueOf(port), "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=" + timeoutSec]
    if (keyPath) {
        sshCmd.add("-i")
        sshCmd.add(keyPath)
    }
    sshCmd.add("${user}@${host}")
    sshCmd.add(cmdStr)

    ProcessBuilder pb = new ProcessBuilder(sshCmd)
    try {
        Process proc = pb.start()
        StringBuilder stdoutBuf = new StringBuilder()
        StringBuilder stderrBuf = new StringBuilder()
        Thread th1 = Thread.startDaemon {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"))
                String l; while ((l = r.readLine()) != null) stdoutBuf.append(l).append('\n')
            } catch (Exception ignored) {}
        }
        Thread th2 = Thread.startDaemon {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), "UTF-8"))
                String l; while ((l = r.readLine()) != null) stderrBuf.append(l).append('\n')
            } catch (Exception ignored) {}
        }
        proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        th1.join(500)
        th2.join(500)

        Map res = [
            status: proc.exitValue() == 0 ? "success" : "error",
            session: alias,
            host: host,
            command: cmdStr,
            returncode: proc.exitValue(),
            stdout: stdoutBuf.toString().trim(),
            stderr: stderrBuf.toString().trim()
        ]

        if (!p.binding_mode || p.json == true) println(JsonHelper.toJson(res))
        return res
    } catch (Exception e) {
        return [status: "error", message: e.message]
    }
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams, binding)
if (rawParams != null || isDirectBinding(binding)) parsed.binding_mode = true
return executeSSHCmd(parsed)
