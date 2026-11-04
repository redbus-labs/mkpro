package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.mkpro.utils.PathUtils;
import io.reactivex.rxjava3.core.Single;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incremental Codebase Symbol Graph & Indexer (`find_symbol` and `find_usages`).
 * Maintains an in-memory cache keyed by normalized Path and lastModifiedTime.
 */
public class SymbolIndexTool {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
        ".git", "target", "node_modules", ".mkpro", "build", "dist", "out", ".idea", ".vscode", ".gradle", ".cache"
    );

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".java", ".py", ".ts", ".js", ".go"
    );

    private static final Set<String> CONTROL_KEYWORDS = Set.of(
        "if", "for", "while", "switch", "catch", "return", "throw", "new", "synchronized",
        "super", "this", "else", "try", "finally", "do", "case", "default", "assert"
    );

    // Java/TS/JS class/interface/enum/record declarations
    private static final Pattern TYPE_DECL_PATTERN = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|export|default)\\s+)*(class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );

    // Go type struct/interface
    private static final Pattern GO_TYPE_PATTERN = Pattern.compile(
        "^\\s*type\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(struct|interface)\\b"
    );

    // Python class/def
    private static final Pattern PY_CLASS_PATTERN = Pattern.compile(
        "^\\s*class\\s+([A-Za-z_][A-Za-z0-9_]*)\\b"
    );
    private static final Pattern PY_DEF_PATTERN = Pattern.compile(
        "^\\s*(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("
    );

    // Go func (with optional receiver)
    private static final Pattern GO_FUNC_PATTERN = Pattern.compile(
        "^\\s*func\\s+(?:\\([^)]*\\)\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\("
    );

    // JS/TS function or arrow/method
    private static final Pattern TS_FUNC_PATTERN = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*[<(]"
    );

    // Java / TS method declaration
    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|synchronized|abstract|default|native|strictfp|async)\\s+)+(?:<[^>]+>\\s+)?(?:[A-Za-z0-9_$<>\\[\\].,?]+\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;]*$"
    );

    public static class SymbolDef {
        public final String name;
        public final String kind;
        public final String filePath;
        public final int line;
        public final String signature;

        public SymbolDef(String name, String kind, String filePath, int line, String signature) {
            this.name = name;
            this.kind = kind;
            this.filePath = filePath;
            this.line = line;
            this.signature = signature;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("kind", kind);
            map.put("path", filePath);
            map.put("line", line);
            map.put("signature", signature);
            return map;
        }
    }

    public static class CachedFileSymbols {
        public final Path path;
        public final String relativePath;
        public final long lastModifiedMillis;
        public final List<SymbolDef> symbols;
        public final List<String> lines;

        public CachedFileSymbols(Path path, String relativePath, long lastModifiedMillis,
                                 List<SymbolDef> symbols, List<String> lines) {
            this.path = path;
            this.relativePath = relativePath;
            this.lastModifiedMillis = lastModifiedMillis;
            this.symbols = symbols;
            this.lines = lines;
        }
    }

    private static final Map<Path, CachedFileSymbols> SYMBOL_CACHE = new ConcurrentHashMap<>();
    private static volatile Path rootOverride;

    /**
     * Optional root directory override (useful for unit tests).
     */
    public static void setRootOverride(Path root) {
        rootOverride = root != null ? root.toAbsolutePath().normalize() : null;
        SYMBOL_CACHE.clear();
    }

    /**
     * Invalidate a file in the symbol cache so it is re-indexed on the next query.
     */
    public static void invalidate(Path path) {
        if (path == null) return;
        Path normalized = path.toAbsolutePath().normalize();
        SYMBOL_CACHE.remove(normalized);
    }

    /**
     * Clear the entire symbol cache.
     */
    public static void clearCache() {
        SYMBOL_CACHE.clear();
    }

    /**
     * Refresh the incremental symbol index across the project workspace.
     */
    public static synchronized void refreshIndex() {
        Path root = rootOverride != null ? rootOverride : PathUtils.getProjectPath().toAbsolutePath().normalize();
        if (!Files.exists(root)) return;

        Set<Path> seenFiles = new HashSet<>();
        try {
            scanDirectory(root, root, seenFiles);
        } catch (Exception ignored) {
        }
        SYMBOL_CACHE.keySet().retainAll(seenFiles);
    }

    private static void scanDirectory(Path root, Path current, Set<Path> seenFiles) {
        if (!Files.isDirectory(current)) return;
        try (var stream = Files.list(current)) {
            for (Path entry : (Iterable<Path>) stream::iterator) {
                Path fileNamePath = entry.getFileName();
                if (fileNamePath == null) continue;
                String name = fileNamePath.toString();

                if (Files.isDirectory(entry)) {
                    if (EXCLUDED_DIRS.contains(name) || name.startsWith(".")) {
                        continue;
                    }
                    scanDirectory(root, entry, seenFiles);
                } else if (Files.isRegularFile(entry) && isSupportedSourceFile(name)) {
                    Path normalized = entry.toAbsolutePath().normalize();
                    seenFiles.add(normalized);
                    indexFileIfNeeded(root, normalized);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isSupportedSourceFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    private static void indexFileIfNeeded(Path root, Path file) {
        try {
            FileTime ft = Files.getLastModifiedTime(file);
            long mtime = ft.toMillis();
            CachedFileSymbols existing = SYMBOL_CACHE.get(file);
            if (existing != null && existing.lastModifiedMillis == mtime) {
                return;
            }

            // Skip abnormally large files (> 1.5 MB)
            if (Files.size(file) > 1_500_000) {
                return;
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String relPath = root.relativize(file).toString().replace('\\', '/');
            List<SymbolDef> symbols = extractSymbols(relPath, lines);
            SYMBOL_CACHE.put(file, new CachedFileSymbols(file, relPath, mtime, symbols, lines));
        } catch (Exception ignored) {
        }
    }

    static List<SymbolDef> extractSymbols(String relPath, List<String> lines) {
        List<SymbolDef> results = new ArrayList<>();
        String lowerPath = relPath.toLowerCase(Locale.ROOT);
        boolean isPython = lowerPath.endsWith(".py");
        boolean isGo = lowerPath.endsWith(".go");
        boolean isTsOrJs = lowerPath.endsWith(".ts") || lowerPath.endsWith(".js");

        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) {
                continue;
            }
            int lineNum = i + 1;
            String cleanSig = cleanSignature(trimmed);

            if (isPython) {
                Matcher cm = PY_CLASS_PATTERN.matcher(rawLine);
                if (cm.find()) {
                    results.add(new SymbolDef(cm.group(1), "CLASS", relPath, lineNum, cleanSig));
                    continue;
                }
                Matcher dm = PY_DEF_PATTERN.matcher(rawLine);
                if (dm.find()) {
                    results.add(new SymbolDef(dm.group(1), "FUNCTION", relPath, lineNum, cleanSig));
                }
                continue;
            }

            if (isGo) {
                Matcher tm = GO_TYPE_PATTERN.matcher(rawLine);
                if (tm.find()) {
                    String kind = "interface".equals(tm.group(2)) ? "INTERFACE" : "CLASS";
                    results.add(new SymbolDef(tm.group(1), kind, relPath, lineNum, cleanSig));
                    continue;
                }
                Matcher fm = GO_FUNC_PATTERN.matcher(rawLine);
                if (fm.find()) {
                    results.add(new SymbolDef(fm.group(1), "FUNCTION", relPath, lineNum, cleanSig));
                }
                continue;
            }

            // Java / TS / JS type declarations
            Matcher typeMatcher = TYPE_DECL_PATTERN.matcher(rawLine);
            if (typeMatcher.find()) {
                String kw = typeMatcher.group(1).toUpperCase(Locale.ROOT);
                String name = typeMatcher.group(2);
                results.add(new SymbolDef(name, kw, relPath, lineNum, cleanSig));
                continue;
            }

            if (isTsOrJs) {
                Matcher tfm = TS_FUNC_PATTERN.matcher(rawLine);
                if (tfm.find()) {
                    results.add(new SymbolDef(tfm.group(1), "FUNCTION", relPath, lineNum, cleanSig));
                    continue;
                }
            }

            // Java / TS method declarations
            if (!trimmed.contains("=") && (trimmed.endsWith("{") || trimmed.endsWith(")") || trimmed.contains(") throws "))) {
                Matcher mm = JAVA_METHOD_PATTERN.matcher(rawLine);
                if (mm.find()) {
                    String methodName = mm.group(1);
                    if (!CONTROL_KEYWORDS.contains(methodName)) {
                        results.add(new SymbolDef(methodName, "METHOD", relPath, lineNum, cleanSig));
                    }
                }
            }
        }
        return results;
    }

    private static String cleanSignature(String line) {
        String s = line.trim();
        if (s.endsWith("{")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.length() > 160) {
            s = s.substring(0, 160) + "...";
        }
        return s;
    }

    public static BaseTool createFindSymbolTool() {
        return new BaseTool(
            "find_symbol",
            "Searches the incremental codebase symbol graph for class, interface, enum, record, method, or function definitions matching a query."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                            "query", Schema.builder()
                                .type("STRING")
                                .description("Symbol name or substring to search for (e.g. 'RestApiHandler', 'serveTelemetry').")
                                .build(),
                            "kind", Schema.builder()
                                .type("STRING")
                                .description("Optional symbol kind filter: CLASS, INTERFACE, ENUM, RECORD, METHOD, FUNCTION.")
                                .build(),
                            "limit", Schema.builder()
                                .type("INTEGER")
                                .description("Optional maximum number of matches to return (default: 30).")
                                .build()
                        ))
                        .required(ImmutableList.of("query"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String query = args.get("query") != null ? args.get("query").toString().trim() : "";
                    String kindFilter = args.get("kind") != null ? args.get("kind").toString().trim().toUpperCase(Locale.ROOT) : "";
                    int limit = args.get("limit") instanceof Number ? ((Number) args.get("limit")).intValue() : 30;
                    limit = Math.max(1, Math.min(limit, 200));

                    if (query.isEmpty()) {
                        return Map.of("error", "query parameter is required");
                    }

                    refreshIndex();
                    String qLower = query.toLowerCase(Locale.ROOT);

                    List<SymbolDef> exactMatches = new ArrayList<>();
                    List<SymbolDef> prefixMatches = new ArrayList<>();
                    List<SymbolDef> substringMatches = new ArrayList<>();

                    for (CachedFileSymbols cached : SYMBOL_CACHE.values()) {
                        for (SymbolDef sym : cached.symbols) {
                            if (!kindFilter.isEmpty() && !kindFilter.equals(sym.kind)) {
                                continue;
                            }
                            String nameLower = sym.name.toLowerCase(Locale.ROOT);
                            if (nameLower.equals(qLower)) {
                                exactMatches.add(sym);
                            } else if (nameLower.startsWith(qLower)) {
                                prefixMatches.add(sym);
                            } else if (nameLower.contains(qLower)) {
                                substringMatches.add(sym);
                            }
                        }
                    }

                    Comparator<SymbolDef> cmp = Comparator.comparing((SymbolDef s) -> s.filePath)
                        .thenComparingInt(s -> s.line);
                    exactMatches.sort(cmp);
                    prefixMatches.sort(cmp);
                    substringMatches.sort(cmp);

                    List<Map<String, Object>> symbolsOut = new ArrayList<>();
                    for (SymbolDef s : exactMatches) {
                        if (symbolsOut.size() >= limit) break;
                        symbolsOut.add(s.toMap());
                    }
                    for (SymbolDef s : prefixMatches) {
                        if (symbolsOut.size() >= limit) break;
                        symbolsOut.add(s.toMap());
                    }
                    for (SymbolDef s : substringMatches) {
                        if (symbolsOut.size() >= limit) break;
                        symbolsOut.add(s.toMap());
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("query", query);
                    if (!kindFilter.isEmpty()) {
                        resp.put("kind", kindFilter);
                    }
                    resp.put("indexedFiles", SYMBOL_CACHE.size());
                    resp.put("count", symbolsOut.size());
                    resp.put("symbols", symbolsOut);
                    return resp;
                });
            }
        };
    }

    public static BaseTool createFindUsagesTool() {
        return new BaseTool(
            "find_usages",
            "Finds all definitions and word-boundary identifier usages/references of a symbol across indexed source files."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                            "symbol", Schema.builder()
                                .type("STRING")
                                .description("Exact symbol identifier to find usages for.")
                                .build(),
                            "limit", Schema.builder()
                                .type("INTEGER")
                                .description("Optional maximum number of usage locations to return (default: 40).")
                                .build()
                        ))
                        .required(ImmutableList.of("symbol"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String symbol = args.get("symbol") != null ? args.get("symbol").toString().trim() : "";
                    int limit = args.get("limit") instanceof Number ? ((Number) args.get("limit")).intValue() : 40;
                    limit = Math.max(1, Math.min(limit, 250));

                    if (symbol.isEmpty()) {
                        return Map.of("error", "symbol parameter is required");
                    }

                    refreshIndex();

                    Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
                    List<Map<String, Object>> definitions = new ArrayList<>();
                    List<Map<String, Object>> usages = new ArrayList<>();

                    List<CachedFileSymbols> files = new ArrayList<>(SYMBOL_CACHE.values());
                    files.sort(Comparator.comparing(f -> f.relativePath));

                    for (CachedFileSymbols cached : files) {
                        Set<Integer> defLines = new HashSet<>();
                        for (SymbolDef def : cached.symbols) {
                            if (def.name.equals(symbol)) {
                                definitions.add(def.toMap());
                                defLines.add(def.line);
                            }
                        }

                        if (usages.size() >= limit) continue;

                        List<String> lines = cached.lines;
                        for (int i = 0; i < lines.size(); i++) {
                            if (usages.size() >= limit) break;
                            int lineNum = i + 1;
                            if (defLines.contains(lineNum)) continue;

                            String line = lines.get(i);
                            if (!line.contains(symbol)) continue;

                            String trimmed = line.trim();
                            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("#")) {
                                continue;
                            }

                            if (wordPattern.matcher(line).find()) {
                                Map<String, Object> u = new LinkedHashMap<>();
                                u.put("path", cached.relativePath);
                                u.put("line", lineNum);
                                u.put("snippet", trimmed.length() > 180 ? trimmed.substring(0, 180) + "..." : trimmed);
                                usages.add(u);
                            }
                        }
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("symbol", symbol);
                    resp.put("indexedFiles", SYMBOL_CACHE.size());
                    resp.put("definitionCount", definitions.size());
                    resp.put("definitions", definitions);
                    resp.put("usageCount", usages.size());
                    resp.put("usages", usages);
                    return resp;
                });
            }
        };
    }

    public static List<BaseTool> createSuite() {
        return List.of(createFindSymbolTool(), createFindUsagesTool());
    }
}
