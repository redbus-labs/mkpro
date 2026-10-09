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
        if (t == "--staged" || t == "--cached") {
            p.staged = true
        } else if (t == "--stat") {
            p.stat = true
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
            if (pos == 0 && !p.path) p.path = t
            pos++
        }
    }
    return p
}

Map executeGitDiff(Map p) {
    String repoPath = p.repo_path ? p.repo_path.toString() : "."
    File repoDir = new File(repoPath).getAbsoluteFile()

    boolean staged = p.staged == true || p.cached == true || (p.staged != null && p.staged.toString().equalsIgnoreCase("true"))
    String ref = p.ref != null ? p.ref.toString() : (p.commit != null ? p.commit.toString() : (p.target != null ? p.target.toString() : null))
    String targetPath = p.path ? p.path.toString() : null

    List<String> diffArgs = ["diff"]
    if (staged) diffArgs.add("--cached")
    if (ref) diffArgs.add(ref)
    if (targetPath) {
        diffArgs.add("--")
        diffArgs.add(targetPath)
    }

    def (diffCode, diffOut, diffErr) = runGit(diffArgs, repoDir)
    if (diffCode != 0) {
        System.err.println("Error executing git diff: ${diffErr}")
        return [status: "error", message: diffErr]
    }

    List<String> statArgs = ["diff", "--stat"]
    if (staged) statArgs.add("--cached")
    if (ref) statArgs.add(ref)
    if (targetPath) {
        statArgs.add("--")
        statArgs.add(targetPath)
    }
    def (_, statOut, _2) = runGit(statArgs, repoDir)

    int filesChanged = 0
    if (statOut) {
        for (String line : statOut.split("\\r?\\n")) {
            if (line.contains("file changed") || line.contains("files changed")) {
                Matcher m = Pattern.compile("(\\d+)\\s+files?\\s+changed").matcher(line)
                if (m.find()) filesChanged = Integer.parseInt(m.group(1))
            }
        }
    }

    Map result = [
        diff: diffOut,
        stat: statOut,
        files_changed: filesChanged,
        staged: staged,
        ref: ref
    ]

    if (p.json == true || (p.binding_mode && p.json == true)) {
        println(JsonHelper.toJson(result))
    } else if (!p.binding_mode) {
        println(diffOut.isEmpty() ? "No changes." : diffOut)
    }
    return result
}

Object rawArgs = binding.hasVariable("args") ? binding.getVariable("args") : null
Object rawParams = binding.hasVariable("params") ? binding.getVariable("params") : null
Map parsed = parseArgs(rawArgs, rawParams, binding)
if (rawParams != null || isDirectBinding(binding)) parsed.binding_mode = true
return executeGitDiff(parsed)
