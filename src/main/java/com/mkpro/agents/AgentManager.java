package com.mkpro.agents;

import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.OllamaBaseLM;
import com.google.adk.models.Gemini;
import com.google.adk.models.BedrockBaseLM;
import com.google.adk.models.BaseLlm;
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
import com.mkpro.models.Provider;
import com.mkpro.tools.MkProTools;
import com.mkpro.ActionLogger;
import com.mkpro.CentralMemory;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

public class AgentManager {

    private final InMemorySessionService sessionService;
    private final InMemoryArtifactService artifactService;
    private final InMemoryMemoryService memoryService;
    private final String apiKey;
    private final ActionLogger logger;
    private final CentralMemory centralMemory;

    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BLUE = "\u001b[34m";

    public AgentManager(InMemorySessionService sessionService, 
                        InMemoryArtifactService artifactService, 
                        InMemoryMemoryService memoryService, 
                        String apiKey,
                        ActionLogger logger,
                        CentralMemory centralMemory) {
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.memoryService = memoryService;
        this.apiKey = apiKey;
        this.logger = logger;
        this.centralMemory = centralMemory;
    }

    public Runner createRunner(Map<String, AgentConfig> agentConfigs, String summaryContext) {
        String contextInfo = "\nCurrent Date: " + LocalDate.now() + "\nCurrent Working Directory: " + Paths.get("").toAbsolutePath().toString();

        // Coordinator Model
        AgentConfig coordConfig = agentConfigs.get("Coordinator");
        BaseLlm model = createModel(coordConfig);

        // Core Tools
        List<BaseTool> coderTools = new ArrayList<>();
        coderTools.add(MkProTools.createReadFileTool());
        coderTools.add(MkProTools.createWriteFileTool());
        coderTools.add(MkProTools.createListDirTool());
        coderTools.add(MkProTools.createReadImageTool());

        List<BaseTool> sysAdminTools = new ArrayList<>();
        sysAdminTools.add(MkProTools.createRunShellTool());

        List<BaseTool> testerTools = new ArrayList<>();
        testerTools.addAll(coderTools); // Read/Write/List/Image
        testerTools.add(MkProTools.createRunShellTool());

        List<BaseTool> docWriterTools = new ArrayList<>();
        docWriterTools.add(MkProTools.createReadFileTool());
        docWriterTools.add(MkProTools.createWriteFileTool());
        docWriterTools.add(MkProTools.createListDirTool());

        // Delegation Tools
        List<BaseTool> coordinatorTools = new ArrayList<>();
        
        coordinatorTools.add(createDelegationTool(
            "ask_coder", 
            "Delegates coding tasks to the Coder agent (read/write files, list dirs).",
            "Coder",
            "You are the Coder. You specialize in software engineering. You can read files, write files, list directories, and analyze images. You CANNOT execute shell commands directly. Perform the requested task and provide a concise report.",
            agentConfigs, coderTools, contextInfo
        ));

        coordinatorTools.add(createDelegationTool(
            "ask_sysadmin", 
            "Delegates system command execution to the SysAdmin agent (shell commands).",
            "SysAdmin",
            "You are the System Administrator. You specialize in executing shell commands safely. You can use 'run_shell'. Execute the requested commands and report the output.",
            agentConfigs, sysAdminTools, contextInfo
        ));

        coordinatorTools.add(createDelegationTool(
            "ask_tester", 
            "Delegates testing tasks to the QA/Tester agent (write/run tests).",
            "Tester",
            "You are the QA / Tester Agent. Your goal is to ensure code quality. You can read/write files (to create tests) and run shell commands (to execute test runners). Always analyze the code first, then write a test case, then run it. Report the results.",
            agentConfigs, testerTools, contextInfo
        ));

        coordinatorTools.add(createDelegationTool(
            "ask_doc_writer", 
            "Delegates documentation tasks to the DocWriter agent (read code, write docs).",
            "DocWriter",
            "You are the Documentation Writer. Your goal is to maintain project documentation. You can read codebase structure and files, and write documentation (README.md, Javadocs, etc.). You do NOT run code. Focus on clarity, accuracy, and keeping docs in sync with code.",
            agentConfigs, docWriterTools, contextInfo
        ));

        // Add Coordinator-specific tools
        coordinatorTools.add(MkProTools.createUrlFetchTool());
        coordinatorTools.add(MkProTools.createGetActionLogsTool(logger));
        coordinatorTools.add(MkProTools.createSaveMemoryTool(centralMemory));
        coordinatorTools.add(MkProTools.createReadMemoryTool(centralMemory));
        coordinatorTools.add(MkProTools.createListProjectsTool(centralMemory));
        coordinatorTools.add(MkProTools.createListDirTool()); // Allow coordinator to list dirs too

        LlmAgent coordinatorAgent = LlmAgent.builder()
            .name("Coordinator")
            .description("The main orchestrator agent.")
            .instruction("You are the Coordinator. You interface with the user and manage the workflow. "
                    + "You have four specialized sub-agents: \n" 
                    + "1. **Coder**: Handles all file operations (read, write, analyze images). \n" 
                    + "2. **SysAdmin**: Handles all shell command executions. \n" 
                    + "3. **Tester**: specialized in writing and running unit tests. \n" 
                    + "4. **DocWriter**: specialized in writing and updating documentation (README, Javadocs). \n" 
                    + "Delegate tasks appropriately. Do not try to write files or run commands yourself; you don't have those tools. " 
                    + "You DO have tools to fetch URLs and manage long-term memory. " 
                    + "Always prefer concise answers."
                    + contextInfo
                    + summaryContext)
            .model(model)
            .tools(coordinatorTools)
            .planning(true)
            .build();

        return Runner.builder()
            .agent(coordinatorAgent)
            .appName("mkpro-cli")
            .sessionService(sessionService)
            .artifactService(artifactService)
            .memoryService(memoryService)
            .build();
    }

    private BaseLlm createModel(AgentConfig config) {
        if (config.getProvider() == Provider.GEMINI) {
            return new Gemini(config.getModelName(), apiKey);
        } else if (config.getProvider() == Provider.BEDROCK) {
            return new BedrockBaseLM(config.getModelName(), null);
        } else {
            return new OllamaBaseLM(config.getModelName(), "http://localhost:11434");
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
        try {
            Session subSession = sessionService.createSession(request.getAgentName(), "user").blockingGet();
            
            AgentConfig config = new AgentConfig(request.getProvider(), request.getModelName());
            BaseLlm model = createModel(config);
            
            LlmAgent subAgent = LlmAgent.builder()
                .name(request.getAgentName())
                .instruction(request.getInstruction())
                .model(model)
                .tools(request.getTools())
                .planning(true)
                .build();

            Runner subRunner = Runner.builder()
                .agent(subAgent)
                .appName(request.getAgentName())
                .sessionService(sessionService)
                .artifactService(artifactService)
                .memoryService(memoryService)
                .build();

            StringBuilder output = new StringBuilder();
            Content content = Content.builder().role("user").parts(List.of(Part.fromText(request.getUserPrompt()))).build();
            
            subRunner.runAsync("user", subSession.id(), content)
                  .filter(e -> e.content().isPresent())
                  .blockingForEach(e -> 
                      e.content().flatMap(Content::parts).orElse(Collections.emptyList())
                       .forEach(p -> p.text().ifPresent(output::append))
                  );
            return output.toString();
        } catch (Exception e) {
            return "Error executing sub-agent " + request.getAgentName() + ": " + e.getMessage();
        }
    }
}
