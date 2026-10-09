package com.mkpro.config;

import com.mkpro.utils.PathUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ConfigService {

    public static final String PROP_GEMINI_KEY = "gemini.api.key";
    public static final String PROP_OLLAMA_URL = "ollama.server.url";
    public static final String PROP_TEAM = "active.team";

    private final Properties properties;
    private final Path configPath;

    public ConfigService() {
        this.properties = new Properties();
        this.configPath = PathUtils.getBaseDocumentsPath().resolve("config.properties");
        loadConfig();
    }

    /**
     * Initializes and returns the teams directory path.
     * Creates the directory if it does not exist, and extracts all missing legacy
     * and modular ADK team resources from the classpath/JAR/dev resources.
     *
     * @return Path to the local teams directory
     * @throws IOException If creating directories fails
     */
    public static Path setupTeamsDir() throws IOException {
        Path teamsDir = PathUtils.getProjectPath().resolve("teams");
        if (!Files.exists(teamsDir)) {
            Files.createDirectories(teamsDir);
        }
        exportTeamsResources(teamsDir);
        return teamsDir;
    }

    /**
     * Exports all team resources (both legacy .yaml files and modular ADK team folders
     * like teams/polyglot/ with agent.yaml, configs/, prompts/, sub_agents/, skills/)
     * from the classpath / JAR into the target teams directory.
     * If the target directory already exists, it checks for missing files and subdirectories
     * and extracts any missing resources without overwriting existing user modifications.
     *
     * @param teamsDir Target directory to export resources into
     * @throws IOException If creating directories fails
     */
    public static void exportTeamsResources(Path teamsDir) throws IOException {
        if (!Files.exists(teamsDir)) {
            Files.createDirectories(teamsDir);
        }

        // 1. If running in dev workspace and src/main/resources/teams exists, sync missing files from dev folder
        try {
            Path devTeams = PathUtils.getProjectPath().resolve("src").resolve("main").resolve("resources").resolve("teams");
            if (Files.isDirectory(devTeams) && !devTeams.toAbsolutePath().normalize().equals(teamsDir.toAbsolutePath().normalize())) {
                copyMissingDirectoryContents(devTeams, teamsDir);
            }
        } catch (Exception e) {
            System.err.println("Note: Dev resource sync skipped: " + e.getMessage());
        }

        // 2. Try finding resource URL for "/teams" or "teams"
        URL teamsUrl = ConfigService.class.getResource("/teams");
        if (teamsUrl == null) {
            teamsUrl = ConfigService.class.getClassLoader().getResource("teams");
        }
        if (teamsUrl == null) {
            teamsUrl = Thread.currentThread().getContextClassLoader().getResource("teams");
        }
        if (teamsUrl == null) {
            // Try known resource as anchor
            URL anchorUrl = ConfigService.class.getResource("/teams/polyglot.yaml");
            if (anchorUrl != null) {
                teamsUrl = anchorUrl;
            }
        }

        if (teamsUrl != null) {
            String protocol = teamsUrl.getProtocol();
            if ("file".equalsIgnoreCase(protocol)) {
                try {
                    Path srcTeams = Paths.get(teamsUrl.toURI());
                    if (Files.isRegularFile(srcTeams)) {
                        srcTeams = srcTeams.getParent();
                    }
                    if (srcTeams != null && Files.exists(srcTeams)) {
                        copyMissingDirectoryContents(srcTeams, teamsDir);
                    }
                } catch (Exception e) {
                    System.err.println("Failed walking file resources for teams: " + e.getMessage());
                }
            } else if ("jar".equalsIgnoreCase(protocol)) {
                try {
                    URLConnection conn = teamsUrl.openConnection();
                    if (conn instanceof JarURLConnection jarConn) {
                        JarFile jarFile = jarConn.getJarFile();
                        exportFromJarFile(jarFile, "teams/", teamsDir);
                    }
                } catch (Exception e) {
                    System.err.println("Failed extracting teams from JarURLConnection: " + e.getMessage());
                }
            }
        }

        // 3. If running from a packaged JAR, inspect CodeSource to extract all entries under teams/
        try {
            var codeSource = ConfigService.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path locationPath = Paths.get(codeSource.getLocation().toURI());
                if (Files.isRegularFile(locationPath) && locationPath.toString().endsWith(".jar")) {
                    try (JarFile jarFile = new JarFile(locationPath.toFile())) {
                        exportFromJarFile(jarFile, "teams/", teamsDir);
                    }
                }
            }
        } catch (Exception e) {
            // ignore fallback error
        }

        // 4. Fallback check for known legacy YAML files
        String[] defaultFiles = {"default.yaml", "minimal.yaml", "polyglot.yaml", "adk_updater.yaml"};
        for (String file : defaultFiles) {
            Path dest = teamsDir.resolve(file);
            if (!Files.exists(dest)) {
                try (InputStream is = ConfigService.class.getResourceAsStream("/teams/" + file)) {
                    if (is != null) {
                        Files.copy(is, dest);
                    }
                } catch (IOException ignored) {}
            }
        }
    }

    private static void copyMissingDirectoryContents(Path srcDir, Path targetDir) throws IOException {
        try (var stream = Files.walk(srcDir)) {
            stream.forEach(src -> {
                try {
                    Path rel = srcDir.relativize(src);
                    if (rel.toString().isEmpty()) {
                        return;
                    }
                    Path dest = targetDir.resolve(rel.toString());
                    if (Files.isDirectory(src)) {
                        if (!Files.exists(dest)) {
                            Files.createDirectories(dest);
                        }
                    } else {
                        if (!Files.exists(dest)) {
                            if (dest.getParent() != null && !Files.exists(dest.getParent())) {
                                Files.createDirectories(dest.getParent());
                            }
                            Files.copy(src, dest);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Failed to copy resource " + src + ": " + e.getMessage());
                }
            });
        }
    }

    private static void exportFromJarFile(JarFile jarFile, String prefix, Path targetDir) throws IOException {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            // Match entry starting with prefix, e.g. "teams/" or "/teams/"
            int idx = name.indexOf(prefix);
            if (idx >= 0) {
                String relPath = name.substring(idx + prefix.length());
                if (relPath.isEmpty()) {
                    continue;
                }
                Path dest = targetDir.resolve(relPath);
                if (entry.isDirectory()) {
                    if (!Files.exists(dest)) {
                        Files.createDirectories(dest);
                    }
                } else {
                    if (!Files.exists(dest)) {
                        if (dest.getParent() != null && !Files.exists(dest.getParent())) {
                            Files.createDirectories(dest.getParent());
                        }
                        try (InputStream is = jarFile.getInputStream(entry)) {
                            Files.copy(is, dest);
                        }
                    }
                }
            }
        }
    }

    private void loadConfig() {
        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                properties.load(is);
            } catch (IOException e) {
                System.err.println("Failed to load configuration: " + e.getMessage());
            }
        }
    }

    public String getSetting(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public void saveSetting(String key, String value) {
        properties.setProperty(key, value);
        try {
            PathUtils.ensureDirectoriesExist(configPath);
            try (OutputStream os = Files.newOutputStream(configPath)) {
                properties.store(os, "MkPro Configuration Settings");
            }
        } catch (IOException e) {
            System.err.println("Failed to save configuration: " + e.getMessage());
        }
    }
}