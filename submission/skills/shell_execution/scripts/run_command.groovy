#!/usr/bin/env groovy
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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
            if (t == "-c" && i + 1 < tokens.size()) {
                p.command = tokens[++i]
            } else if (t == "-d" && i + 1 < tokens.size()) {
                p.working_dir = tokens[++i]
            } else if (t == "-t" && i + 1 < tokens.size()) {
                p.timeout = tokens[++i]
            } else if (i + 1 < tokens.size() && !tokens[i + 1].startsWith("-")) {
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

Map executeRunCommand(Map p) {
    String cmdStr = p.command ? p.command.toString() : (p.c ? p.c.toString() : null)
    if (!cmdStr) {
        System.err.println("Error: Command is required.")
        return [status: "error", message: "Command is required."]
    }

    String workingDirStr = p.working_dir ? p.working_dir.toString() : (p.d ? p.d.toString() : ".")
    File workingDir = new File(workingDirStr).getAbsoluteFile()
    int timeoutSec = p.timeout != null ? Integer.parseInt(p.timeout.toString()) : (p.t != null ? Integer.parseInt(p.t.toString()) : 120)

    boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win")
    List<String> cmdList = isWindows ? ["cmd.exe", "/c", cmdStr] : ["/bin/sh", "-c", cmdStr]

    ProcessBuilder pb = new ProcessBuilder(cmdList)
    pb.directory(workingDir)

    if (p.env instanceof Map) {
        Map<String, String> pbEnv = pb.environment()
        ((Map) p.env).each { k, v -> pbEnv.put(k.toString(), v.toString()) }
    }

    long startTime = System.currentTimeMillis()
    StringBuilder stdoutBuf = new StringBuilder()
    StringBuilder stderrBuf = new StringBuilder()

    try {
        Process process = pb.start()

        Thread outThread = Thread.startDaemon {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))
                String line
                while ((line = r.readLine()) != null) {
                    stdoutBuf.append(line).append('\n')
                }
            } catch (Exception ignored) {}
        }

        Thread errThread = Thread.startDaemon {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), "UTF-8"))
                String line
                while ((line = r.readLine()) != null) {
                    stderrBuf.append(line).append('\n')
                }
            } catch (Exception ignored) {}
        }

        boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        double elapsed = Math.round((System.currentTimeMillis() - startTime) / 10.0) / 100.0

        if (!finished) {
            process.destroyForcibly()
            Map res = [
                status: "timeout",
                command: cmdStr,
                returncode: 124,
                stdout: stdoutBuf.toString().trim(),
                stderr: "Command timed out after ${timeoutSec} seconds",
                elapsed_seconds: elapsed
            ]
            if (!p.binding_mode || p.json == true) println(JsonHelper.toJson(res))
            return res
        }

        outThread.join(1000)
        errThread.join(1000)
        int exitCode = process.exitValue()

        Map res = [
            status: exitCode == 0 ? "success" : "error",
            command: cmdStr,
            returncode: exitCode,
            stdout: stdoutBuf.toString().trim(),
            stderr: stderrBuf.toString().trim(),
            elapsed_seconds: elapsed
        ]

        if (!p.binding_mode || p.json == true) {
            println(JsonHelper.toJson(res))
        }
        return res
    } catch (Exception e) {
        double elapsed = Math.round((System.currentTimeMillis() - startTime) / 10.0) / 100.0
        Map res = [
            status: "error",
            command: cmdStr,
            returncode: -1,
            stdout: stdoutBuf.toString().trim(),
            stderr: e.message,
            elapsed_seconds: elapsed
        ]
        if (!p.binding_mode || p.json == true) println(JsonHelper.toJson(res))
        return res
    }
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams, binding)
if (rawParams != null || isDirectBinding(binding)) parsed.binding_mode = true
return executeRunCommand(parsed)
