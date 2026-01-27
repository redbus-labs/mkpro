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
        List<BaseTool> codeEditorTools = new ArrayList<>();
        codeEditorTools.add(MkProTools.createSafeWriteFileTool());
        codeEditorTools.add(MkProTools.createReadFileTool());

        List<BaseTool> coderTools = new ArrayList<>();
        coderTools.add(MkProTools.createReadFileTool());
        // coderTools.add(MkProTools.createWriteFileTool()); // Removed direct write access
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
        
        
            final String BASE_AGENT_POLICY =
   
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


      // Coder Sub-Agents
coderTools.add(createDelegationTool(
    "ask_code_editor",
    "Delegates code modification tasks to the CodeEditor agent.",
    "CodeEditor", BASE_AGENT_POLICY +
    "\n" +
    "Role: Code Editor.\n" +
    "Responsibilities:\n" +
    "- Apply code changes in a safe and controlled manner.\n" +
    "- Always read the target file(s) to establish context before modifying them.\n" +
    "- Use the `safe_write_file` tool to preview all changes and obtain explicit user confirmation prior to writing.\n" +
    "- Ensure changes are minimal, correct, and aligned with the requested task.",
    agentConfigs, codeEditorTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_goal_tracker",
    "Delegates project goal and task tracking to the Goal Tracker agent.",
    "GoalTracker", BASE_AGENT_POLICY +
    "\n" +
    "Role: Goal Tracker.\n" +
    "Responsibilities:\n" +
    "- Maintain a clear list of project goals and TODO items.\n" +
    "- Add, update, and list goals as requested.\n" +
    "- Track progress at an individual task level.\n" +
    "- Ensure all goals are specific, measurable, and up to date.",
    agentConfigs, goalTrackerTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_coder",
    "Delegates software development tasks to the Coder agent.",
    "Coder", BASE_AGENT_POLICY +
    "\n" +
    "Role: Software Engineer.\n" +
    "Responsibilities:\n" +
    "- Analyze and implement requested coding tasks.\n" +
    "- Read files, inspect directory structures, and analyze images when required.\n" +
    "- Do NOT modify files directly.\n" +
    "- Delegate all code changes to the CodeEditor agent via `ask_code_editor`.\n" +
    "- Provide a concise summary of findings, actions taken, and results.",
    agentConfigs, coderTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_sysadmin",
    "Delegates system-level command execution to the SysAdmin agent.",
    "SysAdmin", BASE_AGENT_POLICY +
    "\n" +
    "Role: System Administrator.\n" +
    "Responsibilities:\n" +
    "- Execute shell commands using the `run_shell` tool.\n" +
    "- Ensure commands are safe, minimal, and relevant to the task.\n" +
    "- Capture and report command outputs and errors clearly.",
    agentConfigs, sysAdminTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_tester",
    "Delegates testing and quality assurance tasks to the Tester agent.",
    "Tester", BASE_AGENT_POLICY +
    "\n" +
    "Role: QA / Test Engineer.\n" +
    "Responsibilities:\n" +
    "- Review the existing code to understand expected behavior.\n" +
    "- Design and implement appropriate test cases.\n" +
    "- Execute tests using available tooling and shell commands.\n" +
    "- Perform end-to-end testing using `selenium_*` tools when applicable.\n" +
    "- Report test results, failures, and recommendations.",
    agentConfigs, testerTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_doc_writer",
    "Delegates documentation authoring and maintenance to the DocWriter agent.",
    "DocWriter", BASE_AGENT_POLICY +
    "\n" +
    "Role: Documentation Specialist.\n" +
    "Responsibilities:\n" +
    "- Review code and project structure to understand system behavior.\n" +
    "- Create and update documentation to accurately reflect the codebase.\n" +
    "- Research external references or verify live systems using `selenium_*` tools when necessary.\n" +
    "- Ensure documentation is clear, accurate, and synchronized with current implementation.",
    agentConfigs, docWriterTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_security_auditor",
    "Delegates security review and vulnerability assessment to the Security Auditor agent.",
    "SecurityAuditor", BASE_AGENT_POLICY +
    "\n" +
    "Role: Security Auditor.\n" +
    "Responsibilities:\n" +
    "- Review source code for common vulnerabilities (e.g., SQL injection, XSS, hardcoded secrets).\n" +
    "- Execute security and dependency analysis tools via shell commands where appropriate.\n" +
    "- Document identified risks and recommend concrete remediation steps.",
    agentConfigs, securityAuditorTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_architect",
    "Delegates architectural analysis and design review to the Architect agent.",
    "Architect", BASE_AGENT_POLICY +
    "\n" +
    "Role: Software Architect.\n" +
    "Responsibilities:\n" +
    "- Review system structure, module boundaries, and dependencies.\n" +
    "- Evaluate scalability, maintainability, and design pattern usage.\n" +
    "- Propose refactoring or architectural improvements.\n" +
    "- Do NOT implement or execute code.\n" +
    "- Focus exclusively on high-level design considerations.",
    agentConfigs, architectTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_database_admin",
    "Delegates database-related design and scripting tasks to the Database Admin agent.",
    "DatabaseAdmin", BASE_AGENT_POLICY +
    "\n" +
    "Role: Database Administrator.\n" +
    "Responsibilities:\n" +
    "- Design and review database schemas and migration scripts.\n" +
    "- Write and analyze SQL queries.\n" +
    "- Manage database-related code artifacts without directly operating the database runtime.",
    agentConfigs, databaseTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_devops",
    "Delegates infrastructure, deployment, and CI/CD tasks to the DevOps agent.",
    "DevOps", BASE_AGENT_POLICY +
    "\n" +
    "Role: DevOps Engineer.\n" +
    "Responsibilities:\n" +
    "- Manage deployment configurations and automation pipelines.\n" +
    "- Author Dockerfiles, Kubernetes manifests, and CI/CD definitions.\n" +
    "- Execute infrastructure-related shell commands and cloud CLIs as required.\n" +
    "- Ensure reliability, reproducibility, and security of deployments.",
    agentConfigs, devOpsTools, contextInfo
));

coordinatorTools.add(createDelegationTool(
    "ask_data_analyst",
    "Delegates data processing and analytical tasks to the Data Analyst agent.",
    "DataAnalyst", BASE_AGENT_POLICY +
    "\n" +
    "Role: Data Analyst.\n" +
    "Responsibilities:\n" +
    "- Analyze structured data sets (CSV, JSON, etc.).\n" +
    "- Develop Python scripts using analytical libraries (pandas, numpy).\n" +
    "- Execute analysis workflows via shell commands.\n" +
    "- Present insights with clear explanations and validated results.",
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

        return buildRunner(coordinatorAgent, "mkpro");
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
                    // Fallback to InMemory
                }
            case POSTGRES:
                try {
                    return new PostgresRunner(  agent,   appName);
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
            Runner subRunner = buildRunner(subAgent, "mkpro");

            // 2. Use the runner's session service to create the session
            // This ensures the session exists in the correct store (InMemory, MapDB, Postgres)
            // Use agent name as the user name for better attribution
            Session subSession = subRunner.sessionService().createSession("mkpro", request.getAgentName()).blockingGet();

            Content content = Content.builder().role("user").parts(List.of(Part.fromText(request.getUserPrompt()))).build();
            
            subRunner.runAsync(request.getAgentName(), subSession.id(), content)
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
