package com.mkpro.config;
import com.mkpro.utils.PathUtils;
import java.nio.file.*;
import java.io.*;
import java.util.Properties;

public class ConfigService {
    public static final String PROP_TEAM = "current_team";
    public static final String PROP_GEMINI_KEY = "gemini_api_key";
    public static final String PROP_OLLAMA_URL = "ollama_url";

    public static Path setupTeamsDir() {
        Path teamsDir = PathUtils.getBaseDocumentsPath().resolve("teams");
        try {
            Files.createDirectories(teamsDir);
            Path defaultTeam = teamsDir.resolve("default.yaml");
            if (!Files.exists(defaultTeam)) {
                Files.writeString(defaultTeam, "agents:\n  - name: Coordinator\n    role: Primary assistant\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return teamsDir;
    }

    public static String loadTeamSelection() {
        return new ConfigService().getSetting(PROP_TEAM, "default");
    }

    public static void saveTeamSelection(String teamName) {
        new ConfigService().saveSetting(PROP_TEAM, teamName);
    }

    public String getSetting(String key, String defaultValue) {
        return getProperty(key, defaultValue);
    }

    public void saveSetting(String key, String value) {
        saveProperty(key, value);
    }

    public static String loadSessionId() {
        return getProperty("last_session_id", null);
    }

    public static void saveSessionId(String sessionId) {
        saveProperty("last_session_id", sessionId);
    }

    private static String getProperty(String key, String def) {
        Properties props = new Properties();
        Path path = PathUtils.getBaseDocumentsPath().resolve("config.properties");
        try {
            if (Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    props.load(is);
                    return props.getProperty(key, def);
                }
            }
        } catch (IOException e) {
            // Log error if needed
        }
        return def;
    }

    private static void saveProperty(String key, String val) {
        Properties props = new Properties();
        Path path = PathUtils.getBaseDocumentsPath().resolve("config.properties");
        try {
            PathUtils.ensureDirectoriesExist(path);
            if (Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) { props.load(is); }
            }
            props.setProperty(key, val);
            try (OutputStream os = Files.newOutputStream(path)) { props.store(os, null); }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
