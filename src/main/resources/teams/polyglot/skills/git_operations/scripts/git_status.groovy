#!/usr/bin/env groovy
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

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
            if (pos == 0 && !p.repo_path) p.repo_path = t
            pos++
        }
    }
    return p
}

Map executeGitStatus(Map p) {
    String repoPath = p.repo_path ? p.repo_path.toString() : "."
    File repoDir = new File(repoPath).getAbsoluteFile()

    def (branchCode, branchOut, branchErr) = runGit(["rev-parse", "--abbrev-ref", "HEAD"], repoDir)
    if (branchCode != 0) {
        System.err.println("Error: Not a git repository or git not available at '" + repoPath + "'.")
        return [status: "error", message: "Not a git repository: " + branchErr]
    }

    def (statusCode, statusOut, statusErr) = runGit(["status", "--porcelain=v1", "-b"], repoDir)
    List staged = []
    List unstaged = []
    List untracked = []

    for (String line : statusOut.split("\\r?\\n")) {
        if (line.startsWith("##")) continue
        if (line.length() < 3) continue
        char s1 = line.charAt(0)
        char s2 = line.charAt(1)
        String filePath = line.substring(3).trim()
        if (filePath.contains(" -> ")) {
            filePath = filePath.substring(filePath.indexOf(" -> ") + 4).trim()
        }

        if (s1 in ['M' as char, 'A' as char, 'D' as char, 'R' as char, 'C' as char]) {
            staged.add([status: String.valueOf(s1), path: filePath])
        }
        if (s2 in ['M' as char, 'D' as char]) {
            unstaged.add([status: String.valueOf(s2), path: filePath])
        }
        if (s1 == ('?' as char) && s2 == ('?' as char)) {
            untracked.add(filePath)
        }
    }

    boolean isClean = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty()

    Map result = [
        branch: branchOut,
        clean: isClean,
        staged: staged,
        unstaged: unstaged,
        untracked: untracked,
        stats: [
            staged_count: staged.size(),
            unstaged_count: unstaged.size(),
            untracked_count: untracked.size()
        ]
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
return executeGitStatus(parsed)
