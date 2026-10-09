#!/usr/bin/env groovy
import java.io.File
import java.nio.file.Files
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

Map parseMemoryJson(String json) {
    Map entries = [:]
    if (!json || json.trim().isEmpty()) return entries
    Pattern p = Pattern.compile('"([^"]+)"\\s*:\\s*\\{([^{}]+)\\}')
    Matcher m = p.matcher(json)
    while (m.find()) {
        String keyName = m.group(1)
        if (keyName == "entries") continue
        String block = m.group(2)
        Map entry = [:]
        Pattern fieldPattern = Pattern.compile('"([^"]+)"\\s*:\\s*("(?:[^"\\\\]|\\\\.)*"|[^,}\\r\\n]+)')
        Matcher fm = fieldPattern.matcher(block)
        while (fm.find()) {
            String fKey = fm.group(1)
            String fVal = fm.group(2).trim()
            if (fVal.startsWith(""") && fVal.endsWith(""") && fVal.length() >= 2) {
                fVal = fVal.substring(1, fVal.length() - 1)
                fVal = fVal.replace("\\"", """).replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
            }
            entry[fKey] = fVal
        }
        if (!entry.isEmpty()) {
            entries[keyName] = entry
        }
    }
    return entries
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
        if (t == "--all") {
            p.all = true
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
            if (pos == 0 && !p.key) p.key = t
            pos++
        }
    }
    return p
}

Map executeRecallMemory(Map p) {
    String storePath = p.store ? p.store.toString() : ".mkpro/project_memory.json"
    File storeFile = new File(storePath).getAbsoluteFile()

    if (!storeFile.exists()) {
        Map emptyRes = [results_count: 0, entries: [:], message: "No memory store found.", store: storeFile.getAbsolutePath()]
        if (!p.binding_mode || p.json == true) println(JsonHelper.toJson(emptyRes))
        return emptyRes
    }

    Map entries = [:]
    try {
        String content = new String(Files.readAllBytes(storeFile.toPath()), "UTF-8")
        entries = parseMemoryJson(content)
    } catch (Exception e) {
        System.err.println("Error reading memory: " + e.message)
        return [status: "error", message: e.message]
    }

    String keyFilter = p.key ? p.key.toString() : null
    String queryFilter = p.filter ? p.filter.toString() : (p.q ? p.q.toString() : null)
    String tagFilter = p.tag ? p.tag.toString() : null

    Map matches = [:]
    if (keyFilter) {
        if (entries.containsKey(keyFilter)) {
            matches[keyFilter] = entries[keyFilter]
        }
    } else if (queryFilter) {
        String q = queryFilter.toLowerCase()
        entries.each { k, v ->
            String c = v.content ? v.content.toString().toLowerCase() : ""
            String t = v.tag ? v.tag.toString().toLowerCase() : ""
            if (k.toLowerCase().contains(q) || c.contains(q) || t.contains(q)) {
                matches[k] = v
            }
        }
    } else if (tagFilter) {
        entries.each { k, v ->
            if (v.tag && v.tag.toString().equalsIgnoreCase(tagFilter)) {
                matches[k] = v
            }
        }
    } else {
        matches = entries
    }

    Map result = [
        results_count: matches.size(),
        entries: matches,
        store: storeFile.getAbsolutePath()
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
return executeRecallMemory(parsed)
