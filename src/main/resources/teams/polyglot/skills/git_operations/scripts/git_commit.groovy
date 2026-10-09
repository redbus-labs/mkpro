#!/usr/bin/env groovy
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
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

List runGit(List<String> cmdArgs, File workingDir) {
    List<String> fullCmd = ["git"] + cmdArgs
    ProcessBuilder pb = new ProcessBuilder(fullCmd)
    pb.directory(workingDir)
    try {
        Process proc = pb.start()
        StringBuilder out = new StringBuilder()
        StringBuilder err = new StringBuilder()
        Thread t1 = Thread.startDaemon {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"))
                String l; while ((l = r.readLine()) != null) out.append(l).append('\n')
            } catch (Exception ignored) {}
        }
        Thread t2 = Thread.startDaemon {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), "UTF-8"))
                String l; while ((l = r.readLine()) != null) err.append(l).append('\n')
            } catch (Exception ignored) {}
        }
        proc.waitFor()
        t1.join(500)
        t2.join(500)
        return [proc.exitValue(), out.toString().trim(), err.toString().trim()]
    } catch (Exception e) {
        return [-1, "", e.message]
    }
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
        if (t == "-a" || t == "--all") {
            p.all = true
        } else if (t == "-m" || t == "--message") {
            if (i + 1 < tokens.size()) p.message = tokens[++i]
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
            if (pos == 0 && !p.message) p.message = t
            pos++
        }
    }
    return p
}

String sanitizeMessage(String msg) {
    if (msg == null) return ""
    String c = msg.replaceAll("[A-Za-z]:\\[Uu]sers\\[^\\]+\\", "./")
    c = c.replaceAll("/home/[^/]+/", "~/")
    return c
}

Map executeGitCommit(Map p) {
    String repoPath = p.repo_path ? p.repo_path.toString() : "."
    File repoDir = new File(repoPath).getAbsoluteFile()
    String message = p.message ? p.message.toString() : (p.m ? p.m.toString() : null)

    if (!message) {
        System.err.println("Error: Commit message is required.")
        return [status: "error", message: "Commit message is required."]
    }

    boolean stageAll = p.all == true || p.a == true || (p.all != null && p.all.toString().equalsIgnoreCase("true"))

    List<String> forbiddenPatterns = [
        "\\.env($|\\.local)",
        ".*\\.pem$",
        ".*\\.key$",
        "id_rsa",
        "credentials\\.json"
    ]

    def (_, stagedOut, _2) = runGit(["diff", "--cached", "--name-only"], repoDir)
    if (!stagedOut.isEmpty()) {
        for (String line : stagedOut.split("\\r?\\n")) {
            String cleanLine = line.trim()
            if (cleanLine.isEmpty()) continue
            String fileName = new File(cleanLine).getName()
            for (String pat : forbiddenPatterns) {
                if (fileName.matches("(?i)" + pat)) {
                    System.err.println("Security Alert: Attempting to commit forbidden secret file '" + cleanLine + "'. Commit aborted.")
                    return [status: "error", message: "Forbidden secret file detected: " + cleanLine]
                }
            }
        }
    }

    String sanitizedMsg = sanitizeMessage(message)

    List<String> commitArgs = ["commit"]
    if (stageAll) commitArgs.add("-a")
    commitArgs.add("-m")
    commitArgs.add(sanitizedMsg)

    def (code, commitOut, commitErr) = runGit(commitArgs, repoDir)
    if (code != 0) {
        System.err.println("Git commit failed: " + commitErr)
        return [status: "error", message: commitErr, output: commitOut]
    }

    def (revCode, revOut, _) = runGit(["rev-parse", "--short", "HEAD"], repoDir)
    String commitHash = revCode == 0 ? revOut : null

    Map result = [
        status: "success",
        commit_hash: commitHash,
        message: sanitizedMsg,
        output: commitOut
    ]

    if (!p.binding_mode || p.json == true) {
        println(JsonHelper.toJson(result))
    }
    return result
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams, binding)
if (rawParams != null || isDirectBinding(binding)) parsed.binding_mode = true
return executeGitCommit(parsed)
