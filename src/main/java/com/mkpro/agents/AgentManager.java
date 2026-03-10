package com.mkpro.agents;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.OllamaBaseLM;
import com.google.adk.models.Gemini;
import com.google.adk.models.BedrockBaseLM;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.MapDbRunner;
import com.google.adk.runner.PostgresRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;

import com.mkpro.models.AgentConfig;
import com.mkpro.models.AgentRequest;
import com.mkpro.models.AgentStat;
import com.mkpro.models.Provider;
import com.mkpro.models.RunnerType;
import com.mkpro.tools.MkProTools;
import com.mkpro.tools.McpServerConnectTools;
import com.mkpro.ActionLogger;
import com.mkpro.CentralMemory;
import com.mkpro.SessionHelper;

import com.google.adk.memory.EmbeddingService;

import java.nio.file.Path;
import com.mkpro.vectorstore.SearchableVectorStore;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.time.LocalDate;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mkpro.models.AgentDefinition;
import com.mkpro.models.AgentsConfig;
import com.google.adk.sessions.BaseSessionService;

public class AgentManager {

    private final InMemorySessionService sessionService;
    private final InMemoryArtifactService artifactService;
    private final InMemoryMemoryService memoryService;
    private final String apiKey;
    private final String ollamaServerUrl;
    private final ActionLogger logger;
    private final CentralMemory centralMemory;
    private final RunnerType runnerType;
    private final Map<String, AgentDefinition> agentDefinitions;
    private final SearchableVectorStore vectorStore;
    private final EmbeddingService embeddingService;

    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BLUE = "\u001b[34m";

    private static final String BASE_AGENT_POLICY =
    "Authority:\n" +
    "- You are an autonomous specialist operating under the Coordinator agent.\n" +
    "- You MUST act only within the scope of your assigned responsibilities.\n" +
    "\n" +
    "General Rules:\n" +
    "- You MUST follow all explicit instructions provided by the Coordinator.\n" +
    "- You MUST analyze the task and relevant context before taking any action.\n" +
    "- You MUST produce deterministic, reproducible outputs.\n" +
    "- You SHOULD minimize unnecessary actions and side effects.\n" +
    "- You MUST clearly report what actions were taken and why.\n" +
    "- You MUST NOT assume missing information; request clarification when required.\n" +
    "\n" +
    "Tool Usage Policy:\n" +
    "- You MUST use only the tools explicitly available to you.\n" +
    "- You MUST NOT simulate or claim tool execution that did not occur.\n" +
    "- You SHOULD prefer read-only operations unless modification is explicitly required.\n" +
    "\n" +
    "Safety & Quality:\n" +
    "- You MUST preserve data integrity and avoid destructive actions.\n" +
    "- You SHOULD favor minimal, reversible changes.\n" +
    "- You MUST report errors, risks, or inconsistencies immediately.\n";

    public AgentManager(InMemorySessionService sessionService, 
                        InMemoryArtifactService artifactService, 
                        InMemoryMemoryService memoryService, 
                        String apiKey,
                        String ollamaServerUrl,
                        ActionLogger logger,
                        CentralMemory centralMemory,
                        RunnerType runnerType,
                        Path teamsConfigPath,
                        SearchableVectorStore vectorStore,
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
    }

    private Map<String, AgentDefinition> loadAgentDefinitions(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            AgentsConfig config = mapper.readValue(is, AgentsConfig.class);
            Map<String, AgentDefinition> defs = new HashMap<>();
            for (AgentDefinition def : config.getAgents()) {
                defs.put(def.getName(), def);
            }
            return defs;
        } catch (IOException e) {
            System.err.println("Error loading agent definitions: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    public Runner createRunner(Map<String, AgentConfig> agentConfigs, String summaryContext) {
        AgentConfig coordConfig = agentConfigs.get("Coordinator");
        AgentDefinition coordDef = agentDefinitions.get("Coordinator");
        
        if (coordConfig == null || coordDef == null) {
            throw new IllegalArgumentException("Coordinator configuration or definition missing.");
        }

        BaseLlm model = createModel(coordConfig);
        
        String username = System.getProperty("user.name");
        String APP_NAME = "mkpro-" + username;

        String contextInfo = "\n\nCurrent Date: " + LocalDate.now() +
                             "\nCurrent Working Directory: " + Paths.get("").toAbsolutePath() + "\n";

        boolean hasEnabledMcpServers = !centralMemory.getEnabledMcpServers().isEmpty();

        // Tool Map for sub-agents
        Map<String, List<BaseTool>> toolMap = new HashMap<>();
        
        List<BaseTool> coderTools = new ArrayList<>();
        coderTools.add(MkProTools.createReadFileTool());
        coderTools.add(MkProTools.createListDirTool());
        coderTools.add(MkProTools.createReadImageTool());
        coderTools.add(MkProTools.createReadClipboardTool());
        coderTools.add(MkProTools.createImageCropTool());
        coderTools.add(McpServerConnectTools.createScanProjectTool());
        coderTools.add(McpServerConnectTools.createSaveComponentTool());
        if (hasEnabledMcpServers) {
            coderTools.add(McpServerConnectTools.createMcpFetchDesignTool(centralMemory));
        }
        if (vectorStore != null && embeddingService != null) {
             coderTools.add(MkProTools.createSearchCodebaseTool(vectorStore, embeddingService));
        }

        List<BaseTool> sysAdminTools = new ArrayList<>();
        sysAdminTools.add(MkProTools.createRunShellTool());
        sysAdminTools.add(MkProTools.createImageCropTool());
        sysAdminTools.add(com.mkpro.tools.BackgroundJobTools.createListBackgroundJobsTool());
        sysAdminTools.add(com.mkpro.tools.BackgroundJobTools.createKillBackgroundJobTool());

        List<BaseTool> testerTools = new ArrayList<>(coderTools);
        testerTools.add(MkProTools.createRunShellTool());

        List<BaseTool> docWriterTools = new ArrayList<>();
        docWriterTools.add(MkProTools.createReadFileTool());
        docWriterTools.add(MkProTools.createWriteFileTool());
        docWriterTools.add(MkProTools.createListDirTool());

        List<BaseTool> codeEditorTools = new ArrayList<>();
        codeEditorTools.add(MkProTools.createSafeWriteFileTool());
        codeEditorTools.add(MkProTools.createReadFileTool());
        codeEditorTools.add(McpServerConnectTools.createScanProjectTool());
        codeEditorTools.add(McpServerConnectTools.createSaveComponentTool());

        // Assign to toolMap
        for (String agentName : agentConfigs.keySet()) {
            if (agentName.contains("SysAdmin")) toolMap.put(agentName, sysAdminTools);
            else if (agentName.contains("Tester")) toolMap.put(agentName, testerTools);
            else if (agentName.contains("DocWriter")) toolMap.put(agentName, docWriterTools);
            else if (agentName.contains("CodeEditor")) toolMap.put(agentName, codeEditorTools);
            else toolMap.put(agentName, coderTools);
        }

        // Coordinator Tools
        List<BaseTool> coordinatorTools = new ArrayList<>();
        coordinatorTools.add(McpServerConnectTools.createListMcpServersTool(centralMemory));
        coordinatorTools.add(McpServerConnectTools.createScanProjectTool());
        if (hasEnabledMcpServers) {
            coordinatorTools.add(McpServerConnectTools.createMcpConnectTool(centralMemory));
            coordinatorTools.add(McpServerConnectTools.createMcpFetchDesignTool(centralMemory));
            coordinatorTools.add(McpServerConnectTools.createSaveComponentTool());
            coordinatorTools.add(McpServerConnectTools.createOpenComponentPreviewTool());
            coordinatorTools.add(McpServerConnectTools.createListComponentsTool());
        }

        // Add delegation tools
        for (String agentName : toolMap.keySet()) {
            if ("Coordinator".equals(agentName)) continue;
            String toolName = "ask_" + agentName.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
            BaseTool tool = createDelegationToolFromDef(agentName, toolName, agentConfigs, toolMap.get(agentName), contextInfo);
            if (tool != null) coordinatorTools.add(tool);
        }

        // Standard Coord tools
        coordinatorTools.add(MkProTools.createUrlFetchTool());
        if (coordConfig.getProvider() == Provider.GEMINI) {
             coordinatorTools.add(MkProTools.createGoogleSearchTool());
        }
        coordinatorTools.add(MkProTools.createReadClipboardTool());
        coordinatorTools.add(MkProTools.createGetActionLogsTool(logger));
        coordinatorTools.add(MkProTools.createSaveMemoryTool(centralMemory));
        coordinatorTools.add(MkProTools.createReadMemoryTool(centralMemory));
        coordinatorTools.add(MkProTools.createListProjectsTool(centralMemory));
        coordinatorTools.add(MkProTools.createListDirTool());

        String mcpContext = McpServerConnectTools.buildMcpContextForAgent(centralMemory);

        LlmAgent coordinatorAgent = LlmAgent.builder()
            .name("Coordinator")
            .description(coordDef.getDescription())
            .instruction(coordDef.getInstruction()
                    + contextInfo
                    + mcpContext
                    + (summaryContext != null ? "\n\nPrevious Context:\n" + summaryContext : ""))
            .model(model)
            .tools(coordinatorTools)
            .planning(true)
            .build();

        return buildRunner(coordinatorAgent, APP_NAME);
    }

    private BaseTool createDelegationToolFromDef(String agentName, String toolName, 
                                                 Map<String, AgentConfig> agentConfigs, 
                                                 List<BaseTool> subAgentTools,
                                                 String contextInfo) {
        AgentDefinition def = agentDefinitions.get(agentName);
        if (def == null) return null;
        return createDelegationTool(toolName, def.getDescription(), agentName, BASE_AGENT_POLICY + "\n" + def.getInstruction(), agentConfigs, subAgentTools, contextInfo);
    }

    private Runner buildRunner(LlmAgent agent, String appName) {
        switch (runnerType) {
            case MAP_DB:
                try {
                    return MapDbRunner.builder()
                        .agent(agent)
                        .appName(appName)
                        .build();
                } catch (Exception e) {
                    System.err.println("Error creating MapDbRunner: " + e.getMessage());
                }
            case POSTGRES:
                try {
                    return new PostgresRunner(agent, appName);
                } catch (Exception e) {
                    System.err.println("Error creating PostgresRunner: " + e.getMessage());
                }
            case IN_MEMORY:
            default:
                return Runner.builder()
                    .agent(agent)
                    .appName(appName)
                    .sessionService(sessionService)
                    .artifactService(artifactService)
                    .memoryService(memoryService)
                    .build();
        }
    }

    private BaseLlm createModel(AgentConfig config) {
        if (config.getProvider() == Provider.GEMINI) {
            return new Gemini(config.getModelName(), apiKey);
        } else if (config.getProvider() == Provider.OLLAMA) {
            return new OllamaBaseLM(config.getModelName(), ollamaServerUrl);
        } else if (config.getProvider() == Provider.BEDROCK) {
            // Base URL only (no /model) - BedrockBaseLM appends /model/{modelId}/converse
            String bedrockUrl = System.getenv("BEDROCK_URL");
            if (bedrockUrl == null || bedrockUrl.isEmpty()) {
                String region = System.getenv("BEDROCK_REGION");
                if (region == null || region.isEmpty()) region = "ap-south-1";
                bedrockUrl = "https://bedrock-runtime." + region + ".amazonaws.com";
            }
            return new BedrockBaseLM(config.getModelName(), bedrockUrl);
        } else {
            return new OllamaBaseLM(config.getModelName(), ollamaServerUrl);
        }
    }

    private BaseTool createDelegationTool(String toolName, String description, String agentName, 
                                          String agentInstruction,
                                          Map<String, AgentConfig> agentConfigs, 
                                          List<BaseTool> subAgentTools,
                                          String contextInfo) {
        return new BaseTool(toolName, description) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "instruction", Schema.builder().type("STRING").description("Instructions for " + agentName + ".").build()
                                ))
                                .required(ImmutableList.of("instruction"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String instruction = (String) args.get("instruction");
                System.out.println(ANSI_BLUE + ">> Delegating to " + agentName + "..." + ANSI_RESET);
                AgentConfig config = agentConfigs.get(agentName);
                
                return Single.fromCallable(() -> {
                    String result = executeSubAgent(new AgentRequest(
                        agentName, 
                        agentInstruction + contextInfo,
                        config.getModelName(),
                        config.getProvider(),
                        instruction,
                        subAgentTools
                    ));
                    return Collections.singletonMap("result", result);
                });
            }
        };
    }

    private String executeSubAgent(AgentRequest request) {
        long startTime = System.currentTimeMillis();
        boolean success = true;
        StringBuilder output = new StringBuilder();
        String username = System.getProperty("user.name");
        String APP_NAME = "mkpro-" + username;
        
        logger.log("SYSTEM", String.format("Delegating task to %s (%s/%s)...", 
            request.getAgentName(), request.getProvider(), request.getModelName()));

        try {
            AgentConfig config = new AgentConfig(request.getProvider(), request.getModelName());
            BaseLlm model = createModel(config);
            
            String augmentedInstruction = request.getInstruction() + 
                "\n\n[System State: Running on Provider: " + request.getProvider() + 
                ", Model: " + request.getModelName() + "]";

            LlmAgent subAgent = LlmAgent.builder()
                .name(request.getAgentName())
                .instruction(augmentedInstruction)
                .model(model)
                .tools(request.getTools())
                .planning(true)
                .build();

            Runner subRunner = buildRunner(subAgent, APP_NAME);
            Session subSession = SessionHelper.createSession(subRunner.sessionService(), request.getAgentName()).blockingGet();

            Content content = Content.builder().role("user").parts(List.of(Part.fromText(request.getUserPrompt()))).build();
            
            subRunner.runAsync(request.getAgentName(), subSession.id(), content)
                  .filter(e -> e.content().isPresent())
                  .blockingForEach(e -> 
                      e.content().flatMap(Content::parts).orElse(Collections.emptyList())
                       .forEach(p -> p.text().ifPresent(output::append))
                  );
            
            String resultStr = output.toString();
            logger.log(request.getAgentName(), resultStr);
            return resultStr;
        } catch (Exception e) {
            success = false;
            return "Error executing sub-agent " + request.getAgentName() + ": " + e.getMessage();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            try {
                AgentStat stat = new AgentStat(
                    request.getAgentName(), 
                    request.getProvider().name(), 
                    request.getModelName(), 
                    duration, 
                    success, 
                    request.getUserPrompt().length(), 
                    output.length()
                );
                centralMemory.saveAgentStat(stat);
            } catch (Exception e) {
                System.err.println("Failed to save agent stats: " + e.getMessage());
            }
        }
    }
}
