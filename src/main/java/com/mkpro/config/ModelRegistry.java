package com.mkpro.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mkpro.utils.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class ModelRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ModelRegistry.class);
    private static final String DEFAULT_REMOTE_URL = "https://raw.githubusercontent.com/rblab/mkpro/main/src/main/resources/models.yaml";
    private static final String MODELS_FILE = "models.yaml";
    private static final String CONFIG_FILE = "config.properties";
    private static final String PROP_REMOTE_URL = "models.remote.url";
    private static final long UPDATE_INTERVAL_DAYS = 7;

    public static List<String> GEMINI_MODELS = new ArrayList<>();
    public static List<String> BEDROCK_MODELS = new ArrayList<>();
    public static List<String> OLLAMA_MODELS = new ArrayList<>();
    public static List<String> AZURE_MODELS = new ArrayList<>();

    static {
        loadModels();
    }

    private static void loadModels() {
        // Set default lists first
        setDefaultModels();
        
        // Fetch Ollama models dynamically
        fetchOllamaModels();

        Path localFile = PathUtils.getBaseDocumentsPath().resolve(MODELS_FILE);

        try {
            boolean needsMigration = false;
            if (Files.exists(localFile)) {
                // Migration check: if the file contains "ollama:", we force a refresh from resources
                String content = Files.readString(localFile);
                if (content.contains("ollama:")) {
                    logger.info("Local models.yaml contains deprecated ollama section. Migrating...");
                    needsMigration = true;
                }
            }

            // 1. Copy from resources if local file doesn't exist OR migration is needed
            if (!Files.exists(localFile) || needsMigration) {
                PathUtils.ensureDirectoriesExist(localFile);
                try (InputStream is = ModelRegistry.class.getResourceAsStream("/" + MODELS_FILE)) {
                    if (is != null) {
                        Files.copy(is, localFile, StandardCopyOption.REPLACE_EXISTING);
                        logger.info("Refreshed models.yaml from resources.");
                    } else {
                        logger.warn("Could not find models.yaml in resources, using defaults.");
                    }
                }
            }

            // 2. Load from local file
            if (Files.exists(localFile)) {
                loadFromFile(localFile);
            }

            // 3. Check for updates
            if (shouldUpdate(localFile)) {
                updateModelsAsync(localFile);
            }

        } catch (Exception e) {
            logger.error("Error during model registry initialization: {}", e.getMessage());
            // Fallback to defaults already set
        }
    }

    private static void setDefaultModels() {
        GEMINI_MODELS = new ArrayList<>(Arrays.asList(
            "gemini-3.1-flash-lite", "gemini-3.1-pro-preview", "gemini-3-flash-preview",
            "gemini-3-pro", "gemini-3-flash", "gemini-2.0-flash",
            "gemini-2.0-flash-lite-preview-02-05", "gemini-2.0-pro-exp-02-05",
            "gemini-2.0-flash-thinking-exp-01-21", "gemini-1.5-pro",
            "gemini-1.5-pro-latest", "gemini-1.5-pro-002", "gemini-1.5-flash",
            "gemini-1.5-flash-latest", "gemini-1.5-flash-002", "gemini-1.5-flash-8b",
            "gemini-1.5-flash-8b-latest", "gemini-1.5-flash-8b-001"
        ));
        BEDROCK_MODELS = new ArrayList<>(Arrays.asList(
            "anthropic.claude-3-sonnet-20240229-v1:0", "anthropic.claude-3-haiku-20240307-v1:0",
            "anthropic.claude-3-5-sonnet-20240620-v1:0", "meta.llama3-70b-instruct-v1:0",
            "meta.llama3-8b-instruct-v1:0", "amazon.titan-text-express-v1"
        ));
        AZURE_MODELS = new ArrayList<>(Arrays.asList(
            "gpt-5-pro", "gpt-5.5", "gpt-5.1", "gpt-5", "gpt-5-mini",
            "gpt-4o", "gpt-4-turbo", "gpt-35-turbo"
        ));
    }

    @SuppressWarnings("unchecked")
    private static void fetchOllamaModels() {
        CompletableFuture.runAsync(() -> {
            ConfigService config = new ConfigService();
            String defaultUrl = config.getSetting(ConfigService.PROP_OLLAMA_URL, "http://localhost:11434");
            
            if (defaultUrl.endsWith("/")) {
                defaultUrl = defaultUrl.substring(0, defaultUrl.length() - 1);
            }

            List<String> allModels = new ArrayList<>();

            // Fetch from default endpoint
            List<String> defaultModels = fetchModelsFromServer(defaultUrl);
            allModels.addAll(defaultModels);

            // Fetch from all registered endpoints in CentralMemory
            try {
                com.mkpro.CentralMemory memory = com.mkpro.CentralMemory.getInstance();
                List<String> servers = memory.getOllamaServers();
                for (String entry : servers) {
                    int sep = entry.indexOf('|');
                    if (sep >= 0) {
                        String serverName = entry.substring(0, sep);
                        String serverUrl = entry.substring(sep + 1);
                        if (serverUrl.equals(defaultUrl)) continue; // Skip duplicate

                        List<String> serverModels = fetchModelsFromServer(serverUrl);
                        // Prefix with server name for disambiguation
                        for (String model : serverModels) {
                            String prefixed = serverName + "/" + model;
                            if (!allModels.contains(model)) {
                                allModels.add(model); // Add unprefixed if unique
                            }
                            allModels.add(prefixed); // Always add prefixed version
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not fetch models from registered endpoints: {}", e.getMessage());
            }

            if (!allModels.isEmpty()) {
                OLLAMA_MODELS = allModels;
                logger.info("Successfully fetched {} Ollama models from all endpoints.", allModels.size());
            } else {
                // Fallback
                OLLAMA_MODELS = new ArrayList<>(Arrays.asList(
                    "llama3", "qwen2.5-coder", "mistral", "phi3", "codegemma", "starCoder2"
                ));
            }
        });
    }

    @SuppressWarnings("unchecked")
    public static List<String> fetchModelsFromServer(String serverUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> data = mapper.readValue(response.body(), Map.class);
                List<Map<String, Object>> models = (List<Map<String, Object>>) data.get("models");

                if (models != null && !models.isEmpty()) {
                    List<String> fetchedModels = new ArrayList<>();
                    for (Map<String, Object> model : models) {
                        String name = (String) model.get("name");
                        if (name != null) {
                            fetchedModels.add(name);
                        }
                    }
                    return fetchedModels;
                }
            }
        } catch (Exception e) {
            logger.debug("Ollama server not reachable at {}: {}", serverUrl, e.getMessage());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static void loadFromFile(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> data = mapper.readValue(file.toFile(), Map.class);
        if (data != null) {
            if (data.containsKey("gemini")) GEMINI_MODELS = (List<String>) data.get("gemini");
            if (data.containsKey("bedrock")) BEDROCK_MODELS = (List<String>) data.get("bedrock");
            // Ollama is no longer loaded from file
            if (data.containsKey("azure")) AZURE_MODELS = (List<String>) data.get("azure");
            logger.info("Successfully loaded models from {}", file);
        }
    }

    private static boolean shouldUpdate(Path file) {
        try {
            if (!Files.exists(file)) return true;
            Instant lastModified = Files.getLastModifiedTime(file).toInstant();
            return Duration.between(lastModified, Instant.now()).toDays() >= UPDATE_INTERVAL_DAYS;
        } catch (IOException e) {
            return true;
        }
    }

    private static String resolveRemoteUrl() {
        Path configPath = PathUtils.getBaseDocumentsPath().resolve(CONFIG_FILE);
        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                Properties props = new Properties();
                props.load(is);
                String customUrl = props.getProperty(PROP_REMOTE_URL);
                if (customUrl != null && !customUrl.isBlank()) {
                    return customUrl.trim();
                }
            } catch (IOException e) {
                logger.warn("Failed to load {} for remote URL resolution: {}", CONFIG_FILE, e.getMessage());
            }
        }
        return DEFAULT_REMOTE_URL;
    }

    private static void updateModelsAsync(Path localFile) {
        String remoteUrl = resolveRemoteUrl();
        CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(remoteUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() == 200) {
                    byte[] body = response.body();
                    
                    // Validate with Jackson
                    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                    Map<String, Object> data = mapper.readValue(body, Map.class);
                    
                    if (data != null && !data.isEmpty()) {
                        Path tempFile = localFile.resolveSibling(MODELS_FILE + ".tmp");
                        Files.write(tempFile, body);
                        Files.move(tempFile, localFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        
                        // Reload
                        loadFromFile(localFile);
                        logger.info("Models updated successfully from remote repository: {}", remoteUrl);
                    }
                } else {
                    logger.warn("Failed to fetch models from {}. Status code: {}", remoteUrl, response.statusCode());
                }
            } catch (Exception e) {
                logger.warn("Failed to update models from remote ({}): {}", remoteUrl, e.getMessage());
            }
        });
    }

    public static List<String> getAllModels() {
        List<String> all = new ArrayList<>(GEMINI_MODELS);
        all.addAll(BEDROCK_MODELS);
        all.addAll(OLLAMA_MODELS);
        all.addAll(AZURE_MODELS);
        return all;
    }
}
