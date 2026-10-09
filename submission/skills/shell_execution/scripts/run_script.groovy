#!/usr/bin/env groovy
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
            if (i + 1 < tokens.size() && !tokens[i + 1].startsWith("-")) {
                p[k] = tokens[++i]
            } else {
                p[k] = true
            }
        } else {
            if (pos == 0 && !p.script_path) p.script_path = t
            pos++
        }
    }
    return p
}

String detectInterpreter(String scriptPath, String forced) {
    if (forced != null && !forced.trim().isEmpty()) return forced.toLowerCase()
    String lower = scriptPath.toLowerCase()
    if (lower.endsWith(".groovy")) return "groovy"
    if (lower.endsWith(".py")) return "python"
    if (lower.endsWith(".sh") || lower.endsWith(".bash")) {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "sh" : "bash"
    }
    if (lower.endsWith(".js") || lower.endsWith(".ts")) return "node"
    if (lower.endsWith(".cmd") || lower.endsWith(".bat")) return "cmd"
    if (lower.endsWith(".ps1")) return "powershell"
    return "groovy"
}

Map executeRunScript(Map p) {
    String scriptPath = p.script_path ? p.script_path.toString() : null
    String inlineContent = p.inline != null ? p.inline.toString() : (p.script_content != null ? p.script_content.toString() : (p.code != null ? p.code.toString() : null))

    File tempFile = null
    if (inlineContent != null) {
        String interp = p.interpreter ? p.interpreter.toString().toLowerCase() : "groovy"
        String ext = interp == "python" ? ".py" : (interp == "bash" || interp == "sh" ? ".sh" : (interp == "node" ? ".js" : ".groovy"))
        tempFile = File.createTempFile("inline_script_", ext)
        Files.write(tempFile.toPath(), inlineContent.getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        scriptPath = tempFile.getAbsolutePath()
    }

    if (!scriptPath) {
        System.err.println("Error: script_path or inline content is required.")
        return [status: "error", message: "script_path or inline content is required."]
    }

    File scriptFile = new File(scriptPath)
    if (!scriptFile.exists()) {
        System.err.println("Error: Script '${scriptPath}' not found.")
        return [status: "error", message: "Script '${scriptPath}' not found."]
    }

    String interpreter = detectInterpreter(scriptPath, p.interpreter ? p.interpreter.toString() : null)
    String workingDirStr = p.working_dir ? p.working_dir.toString() : "."
    File workingDir = new File(workingDirStr).getAbsoluteFile()
    int timeoutSec = p.timeout != null ? Integer.parseInt(p.timeout.toString()) : 120
    String scriptArgs = p.args != null ? p.args.toString() : ""

    String fullCmd = "${interpreter} \"${scriptFile.getAbsolutePath()}\" ${scriptArgs}".trim()
    boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win")
    List<String> procCmd = isWindows ? ["cmd.exe", "/c", fullCmd] : ["/bin/sh", "-c", fullCmd]

    ProcessBuilder pb = new ProcessBuilder(procCmd)
    pb.directory(workingDir)

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
                script: scriptPath,
                interpreter: interpreter,
                returncode: 124,
                stdout: stdoutBuf.toString().trim(),
                stderr: "Script timed out after ${timeoutSec} seconds",
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
            script: scriptPath,
            interpreter: interpreter,
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
            script: scriptPath,
            interpreter: interpreter,
            returncode: -1,
            stdout: stdoutBuf.toString().trim(),
            stderr: e.message,
            elapsed_seconds: elapsed
        ]
        if (!p.binding_mode || p.json == true) println(JsonHelper.toJson(res))
        return res
    } finally {
        if (tempFile != null && tempFile.exists()) {
            try { tempFile.delete() } catch (Exception ignored) {}
        }
    }
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams, binding)
if (rawParams != null || isDirectBinding(binding)) parsed.binding_mode = true
return executeRunScript(parsed)
