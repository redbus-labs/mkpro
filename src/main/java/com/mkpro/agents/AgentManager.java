package com.mkpro.agents;

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
    private final RunnerType runnerType;

    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BLUE = "\u001b[34m";

    public AgentManager(InMemorySessionService sessionService, 
                        InMemoryArtifactService artifactService, 
                        InMemoryMemoryService memoryService, 
                        String apiKey,
                        ActionLogger logger,
                        CentralMemory centralMemory,
                        RunnerType runnerType) {
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.memoryService = memoryService;
        this.apiKey = apiKey;
        this.logger = logger;
        this.centralMemory = centralMemory;
        this.runnerType = runnerType;
    }

    public Runner createRunner(Map<String, AgentConfig> agentConfigs, String summaryContext) {
        String contextInfo = "\nCurrent Date: " + LocalDate.now() + "\nCurrent Working Directory: " + Paths.get("").toAbsolutePath().toString();

        // Coordinator Model
        AgentConfig coordConfig = agentConfigs.get("Coordinator");
        BaseLlm model = createModel(coordConfig);

        // Core Tools
        // ... (tools logic stays same)
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

        List<BaseTool> securityAuditorTools = new ArrayList<>();
        securityAuditorTools.addAll(coderTools); // Read/Analyze code
        securityAuditorTools.add(MkProTools.createRunShellTool()); // Run audit tools

        List<BaseTool> architectTools = new ArrayList<>();
        architectTools.add(MkProTools.createReadFileTool());
        architectTools.add(MkProTools.createListDirTool());
        architectTools.add(MkProTools.createReadImageTool());

        List<BaseTool> databaseTools = new ArrayList<>();
        databaseTools.addAll(coderTools); // Read/Write SQL files, schemas

        List<BaseTool> devOpsTools = new ArrayList<>();
        devOpsTools.addAll(coderTools); // Read/Write configs (Dockerfiles, k8s, etc.)
        devOpsTools.add(MkProTools.createRunShellTool()); // Execute cloud CLIs, docker commands

        List<BaseTool> dataAnalystTools = new ArrayList<>();
        dataAnalystTools.addAll(coderTools); // Read/Write Python scripts and data files
        dataAnalystTools.add(MkProTools.createRunShellTool()); // Execute python scripts

        List<BaseTool> goalTrackerTools = new ArrayList<>();
        goalTrackerTools.add(MkProTools.createAddGoalTool(centralMemory));
        goalTrackerTools.add(MkProTools.createListGoalsTool(centralMemory));
        goalTrackerTools.add(MkProTools.createUpdateGoalTool(centralMemory));

        List<BaseTool> webTools = new ArrayList<>();
        webTools.add(com.mkpro.tools.SeleniumTools.createNavigateTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createClickTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createTypeTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createScreenshotTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createGetHtmlTool());
        webTools.add(com.mkpro.tools.SeleniumTools.createCloseTool());

        // Add web capabilities to specific agents
        testerTools.addAll(webTools);
        docWriterTools.addAll(webTools);

        // Delegation Tools
        List<BaseTool> coordinatorTools = new ArrayList<>();
        coordinatorTools.addAll(webTools); // Give Coordinator direct web access for research
        
        coordinatorTools.add(createDelegationTool(
            "ask_goal_tracker", 
            "Delegates goal management tasks to the Goal Tracker agent.",
            "GoalTracker",
            "You are the Goal Tracker. You manage the project's goals and TODOs. You can add new goals, list existing ones, and update their status. Keep track of progress item by item. Ensure goals are specific and trackable.",
            agentConfigs, goalTrackerTools, contextInfo
        ));

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
            "You are the QA / Tester Agent. Your goal is to ensure code quality. You can read/write files (to create tests), run shell commands (to execute test runners), and browse the web using **selenium_* tools** (for E2E testing). Always analyze the code first, then write a test case, then run it. Report the results.",
            agentConfigs, testerTools, contextInfo
        ));

        coordinatorTools.add(createDelegationTool(
            "ask_doc_writer", 
            "Delegates documentation tasks to the DocWriter agent (read code, write docs).",
            "DocWriter",
            "You are the Documentation Writer. Your goal is to maintain project documentation. You can read codebase structure and files, write documentation, and browse the web using **selenium_* tools** to research documentation or verify live sites. Focus on clarity, accuracy, and keeping docs in sync with code.",
            agentConfigs, docWriterTools, contextInfo
        ));

        coordinatorTools.add(createDelegationTool(
            "ask_security_auditor", 
            "Delegates security analysis tasks to the Security Auditor agent (scan code, run audit tools).",
            "SecurityAuditor",
            "You are the Security Auditor. Your goal is to identify vulnerabilities. You can read code to find flaws (SQLi, XSS, hardcoded secrets) and run shell commands to execute security scanners (e.g., npm audit, mvn dependency:analyze). Report findings and suggest fixes.",
            agentConfigs, securityAuditorTools, contextInfo
        ));

        coordinatorTools.add(createDelegationTool(
            "ask_architect", 
            "Delegates high-level design and review tasks to the Architect agent.",
            "Architect",
            "You are the Software Architect. Your goal is to ensure system integrity, scalability, and adherence to design patterns. You review code structure, analyze dependencies, and propose refactoring. You do NOT write implementation code or run it. Focus on the 'big picture', cohesion, and coupling.",
            agentConfigs, architectTools, contextInfo
        ));

        coordinatorTools.add(createDelegationTool(
            "ask_database_admin", 
            "Delegates database tasks to the Database Admin agent (SQL, schema, migrations).",
            "DatabaseAdmin",
            "You are the Database Administrator. Your goal is to manage data persistence. You can write SQL queries, create schema migration files, and analyze database structures. You do NOT run the database itself but manage the code and scripts related to it.",
            agentConfigs, databaseTools, contextInfo
        ));

        coordinatorTools.add(createDelegationTool(
            "ask_devops", 
            "Delegates DevOps and Cloud infrastructure tasks to the DevOps agent.",
            "DevOps",
            "You are the DevOps Engineer. Your goal is to manage infrastructure, deployment, and CI/CD pipelines. You can write Dockerfiles, Kubernetes manifests, and CI configs. You can also run shell commands to interact with cloud CLIs (AWS, GCP, Azure) and container tools.",
            agentConfigs, devOpsTools, contextInfo
        ));

        coordinatorTools.add(createDelegationTool(
            "ask_data_analyst", 
            "Delegates data analysis tasks to the Data Analyst agent (Python stats, data processing).",
            "DataAnalyst",
            "You are the Data Analyst. Your goal is to analyze data sets and perform statistical analysis. You primarily write Python scripts (using pandas, numpy, etc.) to process data files (CSV, JSON) and produce insights. You can execute these scripts using the shell. Focus on data accuracy and clear interpretation of results.",
            agentConfigs, dataAnalystTools, contextInfo
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
                    + "You have ten specialized sub-agents: \n" 
                    + "1. **GoalTracker**: specialized in tracking project goals, TODOs, and progress. \n"
                    + "2. **Coder**: Handles all file operations (read, write, analyze images). \n" 
                    + "3. **SysAdmin**: Handles all shell command executions. \n" 
                    + "4. **Tester**: specialized in writing and running unit tests. \n" 
                    + "5. **DocWriter**: specialized in writing and updating documentation. \n" 
                    + "6. **SecurityAuditor**: specialized in finding vulnerabilities and running security scans. \n" 
                    + "7. **Architect**: specialized in high-level design review and structural analysis. \n" 
                    + "8. **DatabaseAdmin**: specialized in SQL, schemas, and migrations. \n" 
                    + "9. **DevOps**: specialized in infrastructure, Docker, and CI/CD. \n" 
                    + "10. **DataAnalyst**: specialized in Python-based data analysis and statistics. \n" 
                    + "Delegate tasks appropriately. Do not try to write files or run commands yourself; you don't have those tools. \n" 
                    + "You DO have tools to fetch URLs, manage long-term memory, and **browse the web using Selenium** (tools starting with 'selenium_'). \n"
                    + "Always prefer concise answers."
                    + contextInfo
                    + summaryContext)
            .model(model)
            .tools(coordinatorTools)
            .planning(true)
            .build();

        return buildRunner(coordinatorAgent, "mkpro-cli");
    }

    private Runner buildRunner(LlmAgent agent, String appName) {
        switch (runnerType) {
            case MAP_DB:
                try {
                    return new MapDbRunner(agent);
                } catch (Exception e) {
                    System.err.println("Error creating MapDbRunner: " + e.getMessage());
                    // Fallback to InMemory
                }
            case POSTGRES:
                try {
                    return new PostgresRunner(agent);
                } catch (Exception e) {
                    System.err.println("Error creating PostgresRunner: " + e.getMessage());
                    // Fallback to InMemory
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
        long startTime = System.currentTimeMillis();
        boolean success = true;
        StringBuilder output = new StringBuilder();
        
        // Log start of execution to persistent logs
        String executionInfo = String.format("Executing %s using %s (%s)", 
            request.getAgentName(), request.getProvider(), request.getModelName());
        logger.log("SYSTEM", executionInfo);

        try {
            AgentConfig config = new AgentConfig(request.getProvider(), request.getModelName());
            BaseLlm model = createModel(config);
            
            // Inject identity into state memory (instruction)
            String augmentedInstruction = request.getInstruction() + 
                "\n\n[System State: Running on Provider: " + request.getProvider() + 
                ", Model: " + request.getModelName() + "]" +
                "\n\nNOTE: You do not have direct access to action logs. If you need historical context or logs to complete a task, state this clearly in your final report so the Coordinator can provide it in the next turn.";

            LlmAgent subAgent = LlmAgent.builder()
                .name(request.getAgentName())
                .instruction(augmentedInstruction)
                .model(model)
                .tools(request.getTools())
                .planning(true)
                .build();

            // 1. Build the runner (which might create its own SessionService)
            Runner subRunner = buildRunner(subAgent, request.getAgentName());

            // 2. Use the runner's session service to create the session
            // This ensures the session exists in the correct store (InMemory, MapDB, Postgres)
            Session subSession = subRunner.sessionService().createSession(request.getAgentName(), "user").blockingGet();

            Content content = Content.builder().role("user").parts(List.of(Part.fromText(request.getUserPrompt()))).build();
            
            subRunner.runAsync("user", subSession.id(), content)
                  .filter(e -> e.content().isPresent())
                  .blockingForEach(e -> 
                      e.content().flatMap(Content::parts).orElse(Collections.emptyList())
                       .forEach(p -> p.text().ifPresent(output::append))
                  );
            
            return output.toString();
        } catch (Exception e) {
            success = false;
            return "Error executing sub-agent " + request.getAgentName() + ": " + e.getMessage();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            int inputLen = request.getUserPrompt().length();
            int outputLen = output.length();
            
            try {
                AgentStat stat = new AgentStat(
                    request.getAgentName(), 
                    request.getProvider().name(), 
                    request.getModelName(), 
                    duration, 
                    success, 
                    inputLen, 
                    outputLen
                );
                centralMemory.saveAgentStat(stat);
            } catch (Exception e) {
                System.err.println("Failed to save agent stats: " + e.getMessage());
            }
        }
    }
}
