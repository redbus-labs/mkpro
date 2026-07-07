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
import com.google.adk.runner.MapDbRunner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
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
import com.mkpro.SessionHelper;
import com.mkpro.tools.*;
import com.mkpro.tools.StatsTools;
import com.mkpro.tools.GraphMemoryTools;
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
import java.util.Collections;
import java.util.Optional;
import java.util.Properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mkpro.models.AgentDefinition;
import com.mkpro.models.AgentsConfig;

public class AgentManager {

    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_RESET = "\u001B[0m";

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
    private final ToolRegistry toolRegistry;
    private final AgentFactory agentFactory;

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
        this.ollamaServerUrl = (ollamaServerUrl == null || ollamaServerUrl.isEmpty()) ? "http://localhost:11434" : ollamaServerUrl;
        this.logger = logger;
        this.centralMemory = centralMemory;
        this.runnerType = runnerType;
        this.agentDefinitions = loadAgentDefinitions(teamsConfigPath);
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.configProperties = new Properties();
        this.toolRegistry = new ToolRegistry(vectorStore, embeddingService);
        this.agentFactory = new AgentFactory();

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
                return new OllamaBaseLM(  config.getModelName(),ollamaServerUrl);
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
            // Automatically detect project context if not already provided or if augmentedContext is empty
            String fullContext = augmentedContext != null ? augmentedContext : "";
            if (fullContext.isEmpty() || !fullContext.contains("DETECTED PROJECT:")) {
                try {
                    com.mkpro.tools.McpProjectScanner.ProjectInfo projectInfo = 
                        com.mkpro.tools.McpProjectScanner.detectProject(java.nio.file.Paths.get("").toAbsolutePath());
                    if (projectInfo != null && !"unknown".equals(projectInfo.type)) {
                        if (!fullContext.isEmpty()) {
                            fullContext += "\n\n";
                        }
                        fullContext += "DETECTED PROJECT:\n" + projectInfo.toString();
                    }
                } catch (Exception ex) {
                    // Ignore context detection errors
                }
            }

            // 1. Build discrete, specialized toolsets from com.mkpro.tools.*
            List<BaseTool> fileSystemTools = new ArrayList<>();
            fileSystemTools.add(FileSystemTools.create());
            fileSystemTools.add(MkProTools.createWriteFileTool());
            fileSystemTools.add(MkProTools.createSafeWriteFileTool());

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

            // Graphify memory tools
            List<BaseTool> graphMemoryTools = new ArrayList<>();
            graphMemoryTools.add(GraphMemoryTools.memorizeFact());
            graphMemoryTools.add(GraphMemoryTools.recallMemory());
            graphMemoryTools.add(GraphMemoryTools.visualizeGraph());

            // URL fetching tools
            List<BaseTool> fetchUrlTools = new ArrayList<>();
            fetchUrlTools.add(FetchUrlTools.create());

            // 2. Aggregate specialized arrays tailored to agent roles
            List<BaseTool> coderTools = new ArrayList<>();
            coderTools.add(FileSystemTools.create()); // read-only file access
            coderTools.addAll(clipboardTools);
            coderTools.addAll(codebaseSearchTools);
            coderTools.addAll(mcpScanTools);
            coderTools.addAll(graphMemoryTools);
            coderTools.addAll(fetchUrlTools);
            // Coder does NOT get write tools or selenium — it reads/analyzes only

            List<BaseTool> codeEditorTools = new ArrayList<>();
            codeEditorTools.add(FileSystemTools.create());
            codeEditorTools.add(MkProTools.createWriteFileTool());
            codeEditorTools.add(MkProTools.createSafeWriteFileTool());
            codeEditorTools.addAll(clipboardTools);

            List<BaseTool> sysAdminTools = new ArrayList<>();
            sysAdminTools.addAll(shellTools);
            sysAdminTools.addAll(fileSystemTools);
            sysAdminTools.addAll(clipboardTools);

            List<BaseTool> gitTools = new ArrayList<>();
            gitTools.addAll(shellTools);
            gitTools.addAll(fileSystemTools);
            gitTools.add(StatsTools.createGetSessionStatsTool());

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
            architectTools.addAll(graphMemoryTools);
            architectTools.addAll(fetchUrlTools);

            List<BaseTool> securityAuditorTools = new ArrayList<>();
            securityAuditorTools.addAll(fileSystemTools);
            securityAuditorTools.addAll(shellTools);
            securityAuditorTools.addAll(clipboardTools);
            securityAuditorTools.addAll(codebaseSearchTools);

            List<BaseTool> databaseAdminTools = new ArrayList<>();
            databaseAdminTools.addAll(fileSystemTools);
            databaseAdminTools.addAll(shellTools);
            databaseAdminTools.addAll(clipboardTools);

            List<BaseTool> dataAnalystTools = new ArrayList<>();
            dataAnalystTools.addAll(fileSystemTools);
            dataAnalystTools.addAll(shellTools);
            dataAnalystTools.addAll(clipboardTools);

            List<BaseTool> docWriterTools = new ArrayList<>();
            docWriterTools.addAll(fileSystemTools);
            docWriterTools.addAll(clipboardTools);
            docWriterTools.addAll(fetchUrlTools);

            List<BaseTool> devOpsTools = new ArrayList<>();
            devOpsTools.addAll(shellTools);
            devOpsTools.addAll(fileSystemTools);
            devOpsTools.addAll(clipboardTools);
            devOpsTools.addAll(codebaseSearchTools);

            List<BaseTool> goalTrackerTools = new ArrayList<>();
            goalTrackerTools.addAll(fileSystemTools);
            goalTrackerTools.addAll(clipboardTools);

            // Create a mapping of agent name to its assigned toolset
            Map<String, List<BaseTool>> toolMap = new HashMap<>();

            for (AgentDefinition def : agentDefinitions.values()) {
                if ("Coordinator".equalsIgnoreCase(def.getName())) continue;

                List<BaseTool> toolsForAgent;

                // DECLARATIVE PATH: if the YAML defines tools explicitly, use ToolRegistry
                if (def.getTools() != null && !def.getTools().isEmpty()) {
                    toolsForAgent = toolRegistry.resolve(def.getTools());
                } else {
                    // LEGACY FALLBACK: name-based matching for YAMLs without tools field
                    toolsForAgent = resolveToolsByAgentName(def.getName(),
                        fileSystemTools, clipboardTools, shellTools, seleniumTools,
                        imageTools, codebaseSearchTools, multiProjectSearchTools,
                        mcpScanTools, graphMemoryTools, fetchUrlTools,
                        coderTools, codeEditorTools, sysAdminTools, gitTools,
                        testerTools, architectTools, securityAuditorTools,
                        databaseAdminTools, dataAnalystTools, docWriterTools,
                        devOpsTools, goalTrackerTools);
                }
                toolMap.put(def.getName(), toolsForAgent);
            }

            List<BaseTool> coordinatorTools = new ArrayList<>();
            coordinatorTools.add(FetchUrlTools.create());
            //coordinatorTools.add(McpServerConnectTools.createListMcpServersTool(centralMemory));

            // Generate delegation tools for all sub-agents and add them to coordinatorTools
            // Determine which agents need full project context injected
            java.util.Set<String> needsProjectContext = java.util.Set.of(
                "coder", "codeeditor", "architect", "securityauditor", "devops",
                "tester", "androiddev", "iosdev", "sysadmin"
            );
            
            for (Map.Entry<String, List<BaseTool>> entry : toolMap.entrySet()) {
                String agentName = entry.getKey();
                List<BaseTool> toolsForAgent = entry.getValue();
                
                // Only inject project context for agents that need it
                String agentContext = needsProjectContext.contains(agentName.toLowerCase()) ? fullContext : "";
                
                String toolName = "ask_" + agentName.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
                BaseTool delegationTool = createDelegationToolFromDef(agentName, toolName, agentConfigs, toolsForAgent, agentContext);
                if (delegationTool != null) {
                    coordinatorTools.add(delegationTool);
                }
            }

            List<BaseAgent> agents = new ArrayList<>();

            // Coordinator LLM initialization
            AgentConfig coordConfig = agentConfigs.getOrDefault("Coordinator", new AgentConfig(Provider.OLLAMA, "devstral-small-2"));
            BaseLlm coordLlm = createLlm(coordConfig);
            
            // Build Coordinator instruction: YAML-defined instruction + project context
            String coordInstruction = fullContext;
            AgentDefinition coordDef = agentDefinitions.get("Coordinator");
            if (coordDef != null && coordDef.getInstruction() != null) {
                coordInstruction = coordDef.getInstruction() + "\n\n" + fullContext;
            }
            
            LlmAgent coordinator = LlmAgent.builder()
                    .name("Coordinator")
                    .description("The main orchestrator agent.")
                    .instruction(coordInstruction)
                    .model(coordLlm)
                    .tools(coordinatorTools) // Expose delegation tools
                    .planning(true) // ENABLE PLANNING LOOP
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
                        .instruction(fullContext + "\n\nSpecific Instruction: " + def.getInstruction())
                        .model(llm)
                        .planning(true); // ENABLE PLANNING LOOP

                if (def.getName() != null) {
                    List<BaseTool> toolsForAgent = toolMap.get(def.getName());
                    if (toolsForAgent != null) {
                        agentBuilder.tools(toolsForAgent);
                    }
                }
                agents.add(agentBuilder.build());
            }

            // Create Runner using Builder
            Runner.Builder agentBuilder;
            if (this.runnerType == RunnerType.MAP_DB) {
                agentBuilder = MapDbRunner.builder();
            } else {
                agentBuilder = Runner.builder();
            }

            agentBuilder
                    .agent(coordinator)
                    .appName("mkpro")
                    .sessionService(sessionService)
                    .artifactService(artifactService)
                    .memoryService(memoryService);

            logger.log("INFO", "Creating runner for type: " + runnerType);
            
            return agentBuilder.build();

        } catch (Exception e) {
            logger.log("ERROR", "Error creating runner: " + e.getMessage());
            return null;
        }
    }

    /**
     * Legacy fallback: resolves tools for an agent based on its name.
     * Used when the YAML definition does not include a declarative 'tools' field.
     */
    @SuppressWarnings("unchecked")
    private List<BaseTool> resolveToolsByAgentName(String agentName,
            List<BaseTool> fileSystemTools, List<BaseTool> clipboardTools,
            List<BaseTool> shellTools, List<BaseTool> seleniumTools,
            List<BaseTool> imageTools, List<BaseTool> codebaseSearchTools,
            List<BaseTool> multiProjectSearchTools, List<BaseTool> mcpScanTools,
            List<BaseTool> graphMemoryTools, List<BaseTool> fetchUrlTools,
            List<BaseTool> coderTools, List<BaseTool> codeEditorTools,
            List<BaseTool> sysAdminTools, List<BaseTool> gitTools,
            List<BaseTool> testerTools, List<BaseTool> architectTools,
            List<BaseTool> securityAuditorTools, List<BaseTool> databaseAdminTools,
            List<BaseTool> dataAnalystTools, List<BaseTool> docWriterTools,
            List<BaseTool> devOpsTools, List<BaseTool> goalTrackerTools) {

        if (agentName == null) return coderTools;
        String nameLower = agentName.toLowerCase();

        if (nameLower.equals("codeeditor") || nameLower.equals("code_editor")) {
            return codeEditorTools;
        } else if (nameLower.equals("coder") || nameLower.equals("developer")) {
            return coderTools;
        } else if (nameLower.equals("securityauditor") || nameLower.equals("security_auditor") || nameLower.contains("security")) {
            return securityAuditorTools;
        } else if (nameLower.equals("databaseadmin") || nameLower.equals("database_admin") || nameLower.equals("dba")) {
            return databaseAdminTools;
        } else if (nameLower.equals("dataanalyst") || nameLower.equals("data_analyst")) {
            return dataAnalystTools;
        } else if (nameLower.equals("sysadmin") || nameLower.equals("sys_admin")) {
            return sysAdminTools;
        } else if (nameLower.equals("devops") || nameLower.contains("sre")) {
            return devOpsTools;
        } else if (nameLower.contains("git") || nameLower.contains("release")) {
            return gitTools;
        } else if (nameLower.contains("tester") || nameLower.contains("qa")) {
            return testerTools;
        } else if (nameLower.contains("architect")) {
            return architectTools;
        } else if (nameLower.contains("doc") || nameLower.contains("writer")) {
            return docWriterTools;
        } else if (nameLower.contains("goal") || nameLower.contains("tracker")) {
            return goalTrackerTools;
        } else if (nameLower.contains("android") || nameLower.contains("ios")) {
            List<BaseTool> mobileTools = new ArrayList<>(coderTools);
            mobileTools.addAll(shellTools);
            return mobileTools;
        } else {
            return coderTools;
        }
    }

    private BaseTool createDelegationToolFromDef(String agentName, String toolName, 
                                                 Map<String, AgentConfig> agentConfigs, 
                                                 List<BaseTool> subAgentTools,
                                                 String contextInfo) {
        AgentDefinition def = agentDefinitions.get(agentName);
        if (def == null) return null;
        return createDelegationTool(toolName, def.getDescription(), agentName, BASE_AGENT_POLICY + "\n" + def.getInstruction(), agentConfigs, subAgentTools, contextInfo);
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
                if (config == null) {
                    config = new AgentConfig(Provider.OLLAMA, "llama3");
                }
                
                final AgentConfig finalConfig = config;
                return Single.fromCallable(() -> {
                    String result = executeSubAgent(new AgentRequest(
                        agentName, 
                        agentInstruction + contextInfo,
                        finalConfig.getModelName(),
                        finalConfig.getProvider(),
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
        
        long[] tokens = {0, 0, 0}; // [prompt, candidates, total]
        String sessId = "default-session";
        
        logger.log("SYSTEM", String.format("Delegating task to %s (%s/%s)...", 
            request.getAgentName(), request.getProvider(), request.getModelName()));

        try {
            AgentConfig config = new AgentConfig(request.getProvider(), request.getModelName());
            BaseLlm model = createLlm(config);
            if (model == null) {
                throw new IllegalStateException("Could not create LLM for " + request.getAgentName());
            }
            
            String augmentedInstruction = request.getInstruction() + 
                "\n\n[System State: Running on Provider: " + request.getProvider() + 
                ", Model: " + request.getModelName() + "]";

            LlmAgent subAgent = LlmAgent.builder()
                .name(request.getAgentName())
                .instruction(augmentedInstruction)
                .model(model)
                .tools(request.getTools())
                .planning(true) // ENABLE PLANNING LOOP
                .build();

            Runner subRunner = Runner.builder()
                    .agent(subAgent)
                    .appName(APP_NAME)
                    .sessionService(sessionService)
                    .artifactService(artifactService)
                    .memoryService(memoryService)
                    .build();

            Session subSession = SessionHelper.createSession(subRunner.sessionService(), request.getAgentName()).blockingGet();
            if (subSession != null && subSession.id() != null) {
                sessId = subSession.id();
            }

            Content content = Content.builder().role("user").parts(List.of(Part.fromText(request.getUserPrompt()))).build();
            
            subRunner.runAsync(request.getAgentName(), subSession.id(), content)
                .blockingForEach(event -> {
                    // Capture Text
                    event.content().ifPresent(c -> {
                        c.parts().orElse(java.util.Collections.emptyList())
                         .forEach(p -> p.text().ifPresent(output::append));
                    });
                    // Capture Tokens
                    event.usageMetadata().ifPresent(u -> {
                        tokens[0] = u.promptTokenCount().orElse(0);
                        tokens[1] = u.candidatesTokenCount().orElse(0);
                        tokens[2] = u.totalTokenCount().orElse(0);
                    });
                });
            
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
                    output.length(),
                    tokens[0],
                    tokens[1],
                    tokens[2],
                    sessId
                );
                centralMemory.saveAgentStat(stat);
            } catch (Exception e) {
                System.err.println("Failed to save agent stats: " + e.getMessage());
            }
        }
    }
}