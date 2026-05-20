package com.mkpro.agents;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.OllamaBaseLM;
import com.google.adk.models.Gemini;
import com.google.adk.models.BedrockBaseLM;
import com.google.adk.models.AzureBaseLM;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.BaseTool;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.Provider;
import com.mkpro.models.RunnerType;
import com.mkpro.tools.*;
import com.mkpro.ActionLogger;
import com.mkpro.CentralMemory;
import com.google.adk.memory.EmbeddingService;
import com.google.adk.memory.MapDBVectorStore;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mkpro.models.AgentDefinition;
import com.mkpro.models.AgentsConfig;

public class AgentManager {

    private final BaseSessionService sessionService;
    private final BaseArtifactService artifactService;
    private final BaseMemoryService memoryService;
    private final String apiKey;
    private final String ollamaServerUrl;
    private final ActionLogger logger;
    private final CentralMemory centralMemory;
    private final RunnerType runnerType;
    private Map<String, AgentDefinition> agentDefinitions;
    private final MapDBVectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final Properties configProperties;

    public AgentManager(BaseSessionService sessionService, 
                        BaseArtifactService artifactService, 
                        BaseMemoryService memoryService, 
                        String apiKey, 
                        String ollamaServerUrl, 
                        ActionLogger logger, 
                        CentralMemory centralMemory, 
                        RunnerType runnerType, 
                        Path teamsConfigPath, 
                        MapDBVectorStore vectorStore, 
                        EmbeddingService embeddingService) {
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.memoryService = memoryService;
        this.apiKey = apiKey;
        this.ollamaServerUrl = ollamaServerUrl;
        this.logger = logger;
        this.centralMemory = centralMemory;
        this.runnerType = runnerType;
        this.agentDefinitions = loadAgentDefinitions(teamsConfigPath);
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.configProperties = new Properties();

        registerAgentDefinitions();
    }

    private void registerAgentDefinitions() {
        for (AgentDefinition def : agentDefinitions.values()) {
            if (centralMemory.getAgentConfigs(def.getName()) == null) {
                Provider provider = Provider.OLLAMA; 
                if (def.getProvider() != null) {
                    try {
                        provider = Provider.valueOf(def.getProvider().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        // Keep default OLLAMA
                    }
                }
                String model = (def.getModel() != null && !def.getModel().isEmpty()) ? def.getModel() : "llama3";
                AgentConfig config = new AgentConfig(provider, model);
                centralMemory.saveAgentConfig(def.getName(), config);
            }
        }
    }

    private Map<String, AgentDefinition> loadAgentDefinitions(Path path) {
        Map<String, AgentDefinition> defs = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path, "*.{yaml,yml}")) {
                for (Path entry : stream) {
                    loadFromFile(entry, mapper, defs);
                }
            } catch (IOException e) {
            }
        } else if (Files.isRegularFile(path)) {
            loadFromFile(path, mapper, defs);
        }
        return defs;
    }

    private void loadFromFile(Path path, ObjectMapper mapper, Map<String, AgentDefinition> defs) {
        try (InputStream is = Files.newInputStream(path)) {
            AgentsConfig config = mapper.readValue(is, AgentsConfig.class);
            if (config != null && config.getAgents() != null) {
                for (AgentDefinition def : config.getAgents()) {
                    defs.put(def.getName(), def);
                }
            }
        } catch (IOException e) {
        }
    }

    public void reloadAgents(Path path) {
        this.agentDefinitions = loadAgentDefinitions(path);
        registerAgentDefinitions();
    }

    public Map<String, AgentDefinition> getAgentDefinitions() {
        return agentDefinitions;
    }

    private BaseLlm createLlm(AgentConfig config) {
        if (config == null || config.getProvider() == null) return null;
        
        switch (config.getProvider()) {
            case GEMINI:
                return Gemini.builder()
                        .apiKey(apiKey)
                        .modelName(config.getModelName())
                        .build();
            case OLLAMA:
                return new OllamaBaseLM(ollamaServerUrl, config.getModelName());
            case BEDROCK:
                return new BedrockBaseLM(config.getModelName());
            case AZURE:
                return new AzureBaseLM(config.getModelName());
            default:
                return null;
        }
    }

    public Runner createRunner(Map<String, AgentConfig> agentConfigs, String augmentedContext) {
        try {
            // 1. Build discrete, specialized toolsets from com.mkpro.tools.*
            List<BaseTool> fileSystemTools = new ArrayList<>();
            fileSystemTools.add(FileSystemTools.create());

            List<BaseTool> clipboardTools = new ArrayList<>();
            clipboardTools.add(ClipboardTools.create());

            List<BaseTool> shellTools = new ArrayList<>();
            shellTools.add(ShellTools.create());

            List<BaseTool> seleniumTools = new ArrayList<>();
            try {
                seleniumTools.add(SeleniumTools.createNavigateTool());
                seleniumTools.add(SeleniumTools.createClickTool());
                seleniumTools.add(SeleniumTools.createTypeTool());
                seleniumTools.add(SeleniumTools.createScreenshotTool());
                seleniumTools.add(SeleniumTools.createGetHtmlTool());
                seleniumTools.add(SeleniumTools.createCloseTool());
            } catch (Exception e) {
                // Selenium driver setup error fallback
            }

            List<BaseTool> imageTools = new ArrayList<>();
            imageTools.add(ImageTools.create());

            List<BaseTool> codebaseSearchTools = new ArrayList<>();
            codebaseSearchTools.add(CodebaseSearchTools.create(vectorStore, embeddingService));

            List<BaseTool> multiProjectSearchTools = new ArrayList<>();
            multiProjectSearchTools.add(MultiProjectSearchTools.create(embeddingService, vectorStore));

            // Remote MCP connectivity capabilities
            List<BaseTool> mcpScanTools = new ArrayList<>();
            mcpScanTools.add(McpServerConnectTools.createScanProjectTool());
            mcpScanTools.add(McpServerConnectTools.createSaveComponentTool());

            // 2. Aggregate specialized arrays tailored to agent roles
            List<BaseTool> coderTools = new ArrayList<>();
            coderTools.addAll(fileSystemTools);
            coderTools.addAll(clipboardTools);
            coderTools.addAll(codebaseSearchTools);
            coderTools.addAll(mcpScanTools);
            coderTools.addAll(seleniumTools);

            List<BaseTool> sysAdminTools = new ArrayList<>();
            sysAdminTools.addAll(shellTools);
            sysAdminTools.addAll(fileSystemTools);
            sysAdminTools.addAll(clipboardTools);

            List<BaseTool> testerTools = new ArrayList<>();
            testerTools.addAll(fileSystemTools);
            testerTools.addAll(clipboardTools);
            testerTools.addAll(shellTools);
            testerTools.addAll(seleniumTools);

            List<BaseTool> architectTools = new ArrayList<>();
            architectTools.addAll(fileSystemTools);
            architectTools.addAll(imageTools);
            architectTools.addAll(multiProjectSearchTools);
            architectTools.addAll(clipboardTools);

            List<BaseTool> docWriterTools = new ArrayList<>();
            docWriterTools.addAll(fileSystemTools);
            docWriterTools.addAll(clipboardTools);
            docWriterTools.addAll(seleniumTools);

            List<BaseTool> goalTrackerTools = new ArrayList<>();
            goalTrackerTools.addAll(fileSystemTools);
            goalTrackerTools.addAll(clipboardTools);

            List<BaseTool> coordinatorTools = new ArrayList<>();
            coordinatorTools.add(McpServerConnectTools.createListMcpServersTool(centralMemory));

            List<BaseAgent> agents = new ArrayList<>();

            // Coordinator LLM initialization
            AgentConfig coordConfig = agentConfigs.getOrDefault("Coordinator", new AgentConfig(Provider.OLLAMA, "devstral-small-2"));
            BaseLlm coordLlm = createLlm(coordConfig);
            LlmAgent coordinator = LlmAgent.builder()
                    .name("Coordinator")
                    .description("The main orchestrator agent.")
                    .instruction(augmentedContext)
                    .model(coordLlm)
                    .tools(coordinatorTools) // Expose list mcp servers tool
                    .build();
            agents.add(coordinator);

            // Dynamically create agents based on team definitions and assign tools equally
            for (AgentDefinition def : agentDefinitions.values()) {
                if ("Coordinator".equalsIgnoreCase(def.getName())) continue;

                Provider provider = Provider.OLLAMA;
                if (def.getProvider() != null) {
                    try {
                        provider = Provider.valueOf(def.getProvider().toUpperCase());
                    } catch (Exception e) {}
                }

                AgentConfig config = agentConfigs.getOrDefault(def.getName(),
                        new AgentConfig(provider, def.getModel()));
                BaseLlm llm = createLlm(config);

                LlmAgent.Builder agentBuilder = LlmAgent.builder()
                        .name(def.getName())
                        .description(def.getDescription())
                        .instruction(augmentedContext + "\n\nSpecific Instruction: " + def.getInstruction())
                        .model(llm);

                // Add tools based on name to ensure equality
                if (def.getName() != null) {
                    String nameLower = def.getName().toLowerCase();
                    if (nameLower.contains("coder") || nameLower.contains("codeeditor") || nameLower.contains("dev") || nameLower.contains("developer")) {
                        agentBuilder.tools(coderTools);
                    } else if (nameLower.contains("sysadmin") || nameLower.contains("admin") || nameLower.contains("sre") || nameLower.contains("devops")) {
                        agentBuilder.tools(sysAdminTools);
                    } else if (nameLower.contains("tester") || nameLower.contains("qa")) {
                        agentBuilder.tools(testerTools);
                    } else if (nameLower.contains("architect") || nameLower.contains("security")) {
                        agentBuilder.tools(architectTools);
                    } else if (nameLower.contains("doc") || nameLower.contains("writer") || nameLower.contains("analyst") || nameLower.contains("data")) {
                        agentBuilder.tools(docWriterTools);
                    } else if (nameLower.contains("goal") || nameLower.contains("tracker")) {
                        agentBuilder.tools(goalTrackerTools);
                    }
                }
                agents.add(agentBuilder.build());
            }

            // Create Runner using Builder
            Runner.Builder runnerBuilder = Runner.builder()
                    .agent(coordinator)
                    .appName("mkpro")
                    .sessionService(sessionService)
                    .artifactService(artifactService)
                    .memoryService(memoryService);

            logger.log("INFO", "Creating runner for type: " + runnerType);
            
            return runnerBuilder.build();

        } catch (Exception e) {
            logger.log("ERROR", "Error creating runner: " + e.getMessage());
            return null;
        }
    }
}