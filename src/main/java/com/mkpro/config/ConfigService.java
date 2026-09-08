package com.mkpro.config;

import com.mkpro.utils.PathUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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

    public static Path setupTeamsDir() throws IOException {
        // Point teamsDir to the project root directory so we load the actual, up-to-date team files
        Path teamsDir = PathUtils.getProjectPath().resolve("teams");
        if (!Files.exists(teamsDir)) {
            Files.createDirectories(teamsDir);
            
            // Extract default teams from resources if not present
            String[] defaults = {"default.yaml", "minimal.yaml", "polyglot.yaml"};
            for (String file : defaults) {
                try (InputStream is = ConfigService.class.getResourceAsStream("/teams/" + file)) {
                    if (is != null) {
                        Files.copy(is, teamsDir.resolve(file));
                    }
                }
            }
        }
        return teamsDir;
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
