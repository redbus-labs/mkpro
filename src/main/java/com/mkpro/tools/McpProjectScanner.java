package com.mkpro.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * McpProjectScanner handles project type detection and output path resolution.
 * Used by save_component and scan_project tools to determine where files belong.
 */
public class McpProjectScanner {

    /**
     * Represents a detected project with its type, root, package, and key directories.
     */
    public static class ProjectInfo {
        public final String type;        // android, ios, react, flutter, web, nextjs, vue, angular, java_maven, java_gradle, unknown
        public final Path root;          // project root directory
        public final String packageName; // e.g. com.example.app (for Android/Java)
        public final Map<String, Path> directories; // key paths like "sources", "resources", "layouts", "components"

        public ProjectInfo(String type, Path root, String packageName, Map<String, Path> directories) {
            this.type = type;
            this.root = root;
            this.packageName = packageName;
            this.directories = directories;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Project: ").append(type).append("\n");
            sb.append("Root: ").append(root).append("\n");
            if (packageName != null) sb.append("Package: ").append(packageName).append("\n");
            sb.append("Directories:\n");
            directories.forEach((k, v) -> sb.append("  ").append(k).append(" → ").append(root.relativize(v)).append("\n"));
            return sb.toString();
        }
    }

    public static ProjectInfo detectProject(Path cwd) {
        Map<String, Path> dirs = new HashMap<>();

        // ── Android (Gradle + app module) ──
        if (Files.exists(cwd.resolve("app/build.gradle")) || Files.exists(cwd.resolve("app/build.gradle.kts"))) {
            String pkg = findAndroidPackage(cwd);
            Path kotlinSrc = findExistingDir(cwd, "app/src/main/kotlin", "app/src/main/java");
            if (kotlinSrc == null) {
                kotlinSrc = cwd.resolve("app/src/main/java");
            }
            Path resSrc = cwd.resolve("app/src/main/res");
            dirs.put("sources", kotlinSrc);
            if (pkg != null) {
                Path pkgDir = kotlinSrc.resolve(pkg.replace('.', '/'));
                dirs.put("package", pkgDir);
                Path uiDir = findExistingDir(pkgDir, "ui", "presentation", "view", "compose", "screen", "screens");
                if (uiDir != null) {
                    dirs.put("ui", uiDir);
                } else {
                    dirs.put("ui", pkgDir.resolve("ui"));
                }
            }
            dirs.put("resources", resSrc);
            dirs.put("layouts", resSrc.resolve("layout"));
            dirs.put("drawables", resSrc.resolve("drawable"));
            dirs.put("values", resSrc.resolve("values"));
            return new ProjectInfo("android", cwd, pkg, dirs);
        }

        // ── Flutter ──
        if (Files.exists(cwd.resolve("pubspec.yaml"))) {
            Path libDir = cwd.resolve("lib");
            dirs.put("sources", libDir);
            Path screensDir = findExistingDir(libDir, "screens", "pages", "views", "ui");
            if (screensDir != null) dirs.put("screens", screensDir);
            Path widgetsDir = findExistingDir(libDir, "widgets", "components");
            if (widgetsDir != null) dirs.put("widgets", widgetsDir);
            return new ProjectInfo("flutter", cwd, null, dirs);
        }

        // ── iOS (Xcode) ──
        try (Stream<Path> s = Files.list(cwd)) {
            boolean hasXcode = s.anyMatch(p -> p.toString().endsWith(".xcodeproj") || p.toString().endsWith(".xcworkspace"));
            if (hasXcode || Files.exists(cwd.resolve("Package.swift"))) {
                Path srcDir = findIosSourceDir(cwd);
                if (srcDir == null) srcDir = cwd;
                dirs.put("sources", srcDir);
                Path viewsDir = findExistingDir(srcDir, "Views", "View", "Screens", "UI");
                if (viewsDir != null) {
                    dirs.put("views", viewsDir);
                } else {
                    dirs.put("views", srcDir.resolve("Views"));
                }
                return new ProjectInfo("ios", cwd, null, dirs);
            }
        } catch (Exception ignored) {}

        // ── React / Next.js / Vue / Angular (package.json) ──
        if (Files.exists(cwd.resolve("package.json"))) {
            String pkgJson = readFileQuiet(cwd.resolve("package.json"));
            String webType = "web";
            if (pkgJson.contains("\"next\"")) webType = "nextjs";
            else if (pkgJson.contains("\"react\"")) webType = "react";
            else if (pkgJson.contains("\"vue\"")) webType = "vue";
            else if (pkgJson.contains("\"@angular/core\"")) webType = "angular";

            Path srcDir = findExistingDir(cwd, "src", "app");
            if (srcDir == null) srcDir = cwd.resolve("src");
            dirs.put("sources", srcDir);
            Path compDir = findExistingDir(srcDir, "components", "views", "pages", "screens");
            if (compDir != null) {
                dirs.put("components", compDir);
            } else {
                dirs.put("components", srcDir.resolve("components"));
            }
            Path publicDir = findExistingDir(cwd, "public", "static");
            if (publicDir == null) publicDir = cwd.resolve("public");
            dirs.put("public", publicDir);
            return new ProjectInfo(webType, cwd, null, dirs);
        }

        // ── Java/Maven ──
        if (Files.exists(cwd.resolve("pom.xml"))) {
            Path javaSrc = findExistingDir(cwd, "src/main/java", "src/main/kotlin");
            if (javaSrc != null) dirs.put("sources", javaSrc);
            Path resSrc = cwd.resolve("src/main/resources");
            if (Files.exists(resSrc)) dirs.put("resources", resSrc);
            Path webDir = findExistingDir(resSrc, "web", "static", "public", "templates");
            if (webDir != null) dirs.put("web", webDir);
            return new ProjectInfo("java_maven", cwd, null, dirs);
        }

        // ── Java/Gradle (non-Android) ──
        if (Files.exists(cwd.resolve("build.gradle")) || Files.exists(cwd.resolve("build.gradle.kts"))) {
            Path javaSrc = findExistingDir(cwd, "src/main/java", "src/main/kotlin");
            if (javaSrc != null) dirs.put("sources", javaSrc);
            return new ProjectInfo("java_gradle", cwd, null, dirs);
        }

        // ── Plain web (index.html exists) ──
        if (Files.exists(cwd.resolve("index.html"))) {
            dirs.put("sources", cwd);
            return new ProjectInfo("web", cwd, null, dirs);
        }

        // ── Unknown ──
        dirs.put("sources", cwd);
        return new ProjectInfo("unknown", cwd, null, dirs);
    }

    /**
     * Given a detected project and a filename, compute the best save path.
     */
    public static Path resolveOutputPath(ProjectInfo project, String filename) {
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) ext = filename.substring(dot).toLowerCase();
        String baseName = dot >= 0 ? filename.substring(0, dot) : filename;

        switch (project.type) {
            case "android": {
                if (ext.equals(".kt") || ext.equals(".java")) {
                    Path target = project.directories.get("ui");
                    if (target == null) target = project.directories.get("package");
                    if (target == null) {
                        Path sources = project.directories.getOrDefault("sources", project.root.resolve("app/src/main/java"));
                        target = findDeepestPackageDir(sources);
                    }
                    return target.resolve(filename);
                }
                if (ext.equals(".xml") && (baseName.startsWith("activity_") || baseName.startsWith("fragment_") ||
                        baseName.startsWith("layout_") || baseName.startsWith("item_"))) {
                    return project.directories.getOrDefault("layouts",
                            project.root.resolve("app/src/main/res/layout")).resolve(filename);
                }
                if (ext.equals(".xml")) {
                    return project.directories.getOrDefault("drawables",
                            project.root.resolve("app/src/main/res/drawable")).resolve(filename);
                }
                return project.directories.getOrDefault("sources", project.root.resolve("app/src/main/java")).resolve(filename);
            }
            case "flutter": {
                if (ext.equals(".dart")) {
                    Path target = project.directories.getOrDefault("screens",
                            project.directories.getOrDefault("sources", project.root.resolve("lib")));
                    return target.resolve(filename);
                }
                return project.root.resolve("lib").resolve(filename);
            }
            case "ios": {
                if (ext.equals(".swift")) {
                    Path target = project.directories.getOrDefault("views",
                            project.directories.getOrDefault("sources", project.root));
                    return target.resolve(filename);
                }
                return project.directories.getOrDefault("sources", project.root).resolve(filename);
            }
            case "react":
            case "nextjs":
            case "vue":
            case "angular": {
                if (ext.equals(".tsx") || ext.equals(".jsx") || ext.equals(".vue") || ext.equals(".ts") || ext.equals(".js")) {
                    Path target = project.directories.getOrDefault("components",
                            project.directories.getOrDefault("sources", project.root.resolve("src")));
                    return target.resolve(filename);
                }
                if (ext.equals(".css") || ext.equals(".scss")) {
                    Path target = project.directories.getOrDefault("components",
                            project.directories.getOrDefault("sources", project.root.resolve("src")));
                    return target.resolve(filename);
                }
                if (ext.equals(".html")) {
                    return project.directories.getOrDefault("public", project.root.resolve("public")).resolve(filename);
                }
                return project.directories.getOrDefault("sources", project.root.resolve("src")).resolve(filename);
            }
            case "java_maven":
            case "java_gradle": {
                if (ext.equals(".html") || ext.equals(".css") || ext.equals(".js")) {
                    return project.directories.getOrDefault("web",
                            project.directories.getOrDefault("resources", project.root.resolve("src/main/resources"))).resolve(filename);
                }
                if (ext.equals(".java") || ext.equals(".kt")) {
                    return project.directories.getOrDefault("sources", project.root.resolve("src/main/java")).resolve(filename);
                }
                return project.directories.getOrDefault("resources", project.root.resolve("src/main/resources")).resolve(filename);
            }
            case "web": {
                return project.directories.getOrDefault("sources", project.root).resolve(filename);
            }
            default:
                return project.root.resolve(filename);
        }
    }

    // ── Detection Helpers ──

    static String findAndroidPackage(Path root) {
        Path manifest = root.resolve("app/src/main/AndroidManifest.xml");
        if (Files.exists(manifest)) {
            String content = readFileQuiet(manifest);
            if (content != null) {
                int idx = content.indexOf("package=\"");
                if (idx >= 0) {
                    int start = idx + 9;
                    int end = content.indexOf("\"", start);
                    if (end > start) return content.substring(start, end);
                }
            }
        }

        for (String gradleFile : new String[]{"app/build.gradle.kts", "app/build.gradle"}) {
            Path gradle = root.resolve(gradleFile);
            if (Files.exists(gradle)) {
                String content = readFileQuiet(gradle);
                if (content != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("namespace\\s*[=(]\\s*\"([^\"]+)\"")
                            .matcher(content);
                    if (m.find()) return m.group(1);
                    m = java.util.regex.Pattern
                            .compile("applicationId\\s*[=(]\\s*\"([^\"]+)\"")
                            .matcher(content);
                    if (m.find()) return m.group(1);
                }
            }
        }

        Path srcDir = findExistingDir(root, "app/src/main/kotlin", "app/src/main/java");
        if (srcDir != null) {
            try (Stream<Path> walk = Files.walk(srcDir, 6)) {
                Optional<String> pkg = walk
                        .filter(p -> p.toString().endsWith(".kt") || p.toString().endsWith(".java"))
                        .limit(5)
                        .map(McpProjectScanner::readFileQuiet)
                        .filter(c -> c != null)
                        .map(c -> {
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("^package\\s+([\\w.]+)")
                                    .matcher(c);
                            return m.find() ? m.group(1) : null;
                        })
                        .filter(p -> p != null)
                        .findFirst();
                if (pkg.isPresent()) return pkg.get();
            } catch (Exception ignored) {}
        }

        return null;
    }

    static Path findDeepestPackageDir(Path sourcesRoot) {
        if (!Files.isDirectory(sourcesRoot)) return sourcesRoot;
        try (Stream<Path> walk = Files.walk(sourcesRoot, 8)) {
            return walk
                    .filter(Files::isDirectory)
                    .filter(d -> {
                        try (Stream<Path> files = Files.list(d)) {
                            return files.anyMatch(f -> {
                                String name = f.getFileName().toString();
                                return name.endsWith(".kt") || name.endsWith(".java");
                            });
                        } catch (Exception e) { return false; }
                    })
                    .reduce((a, b) -> a.getNameCount() >= b.getNameCount() ? a : b)
                    .orElse(sourcesRoot);
        } catch (Exception e) {
            return sourcesRoot;
        }
    }

    static Path findIosSourceDir(Path root) {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.startsWith(".") && !name.equals("Pods") && !name.equals("build") &&
                               !name.endsWith(".xcodeproj") && !name.endsWith(".xcworkspace");
                    })
                    .filter(p -> {
                        try (Stream<Path> inner = Files.walk(p, 2)) {
                            return inner.anyMatch(f -> f.toString().endsWith(".swift"));
                        } catch (Exception e) { return false; }
                    })
                    .findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    static Path findExistingDir(Path base, String... candidates) {
        for (String c : candidates) {
            Path p = base.resolve(c);
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    static String readFileQuiet(Path path) {
        try { return Files.readString(path); } catch (Exception e) { return null; }
    }
}
