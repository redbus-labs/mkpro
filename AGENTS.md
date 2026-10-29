# AGENT.md: MkPro Autonomous Multi-Agent System (v4.9.0)

## 1. Overview & Architecture
MkPro is a Java-based, autonomous multi-agent developer system. It is designed to orchestrate complex software engineering workflows by leveraging large language models (LLMs) through the **Google GenAI ADK** (`adk.version 1.12.1-SNAPSHOT`).

**Key Architectural Pillars:**
- **Core Engine:** Java-based orchestration layer managing agent lifecycles, memory, project-scoped configurations, and task execution.
- **ADK Integration & Multi-Provider Backend:** Built on the Google GenAI ADK with support for **Gemini**, **OpenAI / vLLM** (multi-server management via `/vllm` & `/openai`), **Ollama**, **AWS Bedrock**, **Azure OpenAI**, **Sarvam AI**, and **Jlama**.
- **Multimodal Engine:** Native inline image, audio (`input_audio`), and video (`video_url`) support in `OpenAiBaseLM` & `OllamaBaseLM`, automatic sub-agent media forwarding in `AgentManager`, and multi-provider `VisionTool` (`analyze_image`) & `AudioTranscriptionTool` (`transcribe_audio`).
- **Persistence & Project Scoping:** Uses **MapDB** (`hot/shared split` architecture in `CentralMemory`) for high-performance embedded key-value storage of project-scoped agent configs (`/config` with `/config global` fallback), `/web` and `/compact` settings, project-grouped Sticky Notes & Tasks, and memories.
- **Distributed & Local Queue Worker (`/mq`):** Built-in `RabbitMqWorkerService` supporting `RABBITMQ`, `INTERNAL` (zero-dependency in-memory queue + loopback HTTP bridge on `127.0.0.1:8765`), and `AUTO` fallback modes.
- **Connectivity:** Features persistent SSH sandbox capabilities (via `com.github.mwiede:jsch`) and smart event filtering (`SmartEventFilterPlugin`) for real-time interaction.
- **Web Interface:** Includes a built-in WebUI (Java-WebSocket + j2html) with Academic Research View (`/`) and Classic View (`/classic`), collapsible project Sticky Notes & Tasks (`📌 Notes`), interactive `@` note/task autocomplete, and the 2-tab Model & Server Config modal (`🧠 Models`).

## 2. Prerequisites & Build Commands
- **Java Version:** JDK 21 (defined in `pom.xml`).
- **Build Tool:** Apache Maven.

**Standard Commands:**
- **Build/Compile:** `mvn clean compile`
- **Run Tests:** `mvn test`
- **Package (Fat JAR):** `mvn clean package` (generates `target/mkpro-4.9.0.jar`)
- **Execution & Worker Scripts:** Uses `maven-shade-plugin` to generate an executable JAR with `com.mkpro.MkPro` as the main class. Use provided scripts (`mkpro.sh` / `mkpro.bat`, `mkpro-web.sh` / `mkpro-web.bat`, `mkpro-worker.sh` / `mkpro-worker.bat`, and `mkpro-mq-test.sh` / `mkpro-mq-test.bat`) for standardized entry points.

## 3. Project Structure & Key Packages
- `com.mkpro`: Main application entry (`MkPro`) and central persistent store (`CentralMemory`).
- `com.mkpro.agents`: Definition, tool registry (`ToolRegistry`), and orchestration (`AgentManager`, multimodal sub-agent forwarding, and token-aware context truncation).
- `com.mkpro.commands.impl`: Interactive CLI slash commands (`VllmCommand`, `MqCommand`, `ConfigCommand`, `WebCommand`, `CompactCommand`, etc.).
- `com.mkpro.config`: Configuration management (`ConfigService`) and dynamic model discovery (`ModelRegistry`).
- `com.mkpro.infra.mq`: RabbitMQ and built-in internal queue worker infrastructure (`RabbitMqWorkerService`, `MqConfig`, `MqTaskMessage`, `MqResultMessage`, `MqTestClient`).
- `com.mkpro.models.notes`: Project-scoped and global Sticky Notes (`NoteItem`) and Tasks (`TaskItem`).
- `com.mkpro.routing`: Markov routing (`MarkovRouter`), intent classification (`IntentClassifier`), and Coordinator-centralized routing guidance (`FastRouteEvaluator`).
- `com.mkpro.tools`: Tooling and utility interfaces exposed to agents (`MkProTools`, `VisionTool`, `AudioTranscriptionTool`, `GitTools`, `ReplaceInFileTool`, scripting, SSH, etc.).
- `com.mkpro.ui`: JLine interactive terminal UI (`TerminalUI`) with `@` note/task autocomplete.
- `com.mkpro.web`: WebUI, HTTP/REST Handlers (`RestApiHandler`), and WebSocket server (`WebChatServer`).
- `src/main/resources`: Configuration, default team YAMLs, and web assets (`academic_view.html`, `index.html`).
- `teams/`: Agent configuration and orchestration definitions (included in JAR via `pom.xml` resource configuration).

## 4. Agent Team, Architecture Updates & Roles
The system operates via a collaborative team of specialized agents led by the Coordinator:
- **Coordinator:** Manages overall task decomposition, inter-agent communication, and forwards multimodal attachments to sub-agents. Receives centralized routing guidance via `FastRouteEvaluator.formatRoutingGuidance()` when intent or Markov confidence (`>= 65%`) indicates a recommended specialist, while keeping multi-step planning centralized in the Coordinator.
- **Coder (Unified Coding & CodeEditor):** Responsible for implementation, code analysis, refactoring, and surgical file modifications (`CodeEditor` has been merged into `Coder`). Equipped with `replace_in_file` for surgical edits on large files (>300 lines), `safe_write_file` with diff preview and automatic backups (`Maker.backItUp`), `codebase_search`, `graph_memory`, `vision`, and in-process Groovy scripting. Any legacy routing or Markov references to `CodeEditor` are automatically normalized to `Coder`.
- **DocWriter (Enhanced):** Handles technical documentation updates (`README.md`, `AGENTS.md`, `AGENT.md`, Javadocs). Equipped with `codebase_search`, `git_tools` (`git_log`, `git_diff`, `git_status`, `git_show`), and `replace_in_file` to inspect code and commits and update documentation surgically.
- **Tester:** Manages test suite execution, Selenium E2E validation, and multimodal (`vision`, `audio`) verification.
- **Architect:** Designs system components, maintains Graph Memory, and ensures architectural consistency.
- **GoalTracker & ProductManager**: Manage project goals, PRDs, user stories, and prioritized roadmaps.
- **GitAgent:** Manages VCS operations and appends AI session token statistics to commits.
- **SysAdmin & UbuntuOps:** Manage local environment, persistent Remote Sandbox SSH/SFTP operations, and deployments.
- **SecurityAuditor, DatabaseAdmin, DevOps, DataAnalyst, AndroidDev, IosDev:** Domain specialists for security audits, SQL/DB schemas, CI/CD & containers, data analysis, and mobile engineering.

## 5. v4.9.0 Feature & Configuration Reference

### 5.1 Multi-Server OpenAI / vLLM Support (`/vllm` & `/openai`)
- **CLI Commands:**
  - `/vllm list` — List all configured OpenAI/vLLM servers, API key status, active selection, and `maxContextTokens`.
  - `/vllm add <name> <url> [apiKey] [maxContext]` — Register a new vLLM/OpenAI endpoint (auto-detects `max_model_len` from `/v1/models` if `maxContext` is omitted).
  - `/vllm remove <name>` — Remove an endpoint.
  - `/vllm select <name>` — Set the default active endpoint and rebuild the runner.
  - `/vllm context <name> <limit>` — Update `maxContextTokens` for a named server and rebuild the runner.
  - `/vllm models [name]` — Fetch available models from the selected or specified server.
  - `/vllm status` — Verify connectivity, model count, and context limits across all servers.
- **Context Limits & Models:**
  - Configurable via `config.properties` (`openai.max.context.tokens`, `openai.provider.<name>.max.context.tokens`), env vars, or `/vllm context`.
  - Supports custom model entry in `/config` and includes default Gemma-4 QAT models (`google/gemma-4-31B-it-qat-w4a16-ct`, `google/gemma-4-12B-it-qat-w4a16-ct`, `google/gemma-4-e4B-it-qat-w4a16-ct`, `google/gemma-4-e2B-it-qat-w4a16-ct`).

### 5.2 Multimodal Vision, Audio & Video Support
- **Inline Media:** `OpenAiBaseLM` and `OllamaBaseLM` serialize inline images, audio (`input_audio`), and video (`video_url`).
- **Sub-Agent Forwarding:** `AgentManager` automatically forwards multimodal `Part` artifacts to sub-agents during delegation.
- **Tools:** Multi-provider `VisionTool` (`analyze_image`) and `AudioTranscriptionTool` (`transcribe_audio`) supporting Gemini, OpenAI/vLLM, Ollama, Bedrock, Azure, and Sarvam.

### 5.3 Project-Scoped Configs, Sticky Notes & Tasks (`@` Mentions)
- **Project Scoping:** `/config` (with `/config global` fallback and `/config reset`), `/web`, and `/compact` settings are scoped to the current project path in `CentralMemory`.
- **Collapsible Project Groups:** The left-side Sticky Notes & Tasks panel (`📌 Notes`) groups notes and tasks by project with collapsible stacks (current project expanded by default).
- **`@` Notation & Autocomplete:** Use `@[Note Title]`, `@[Task: Title]`, `@slug`, or `@id` in chat and CLI (resolved on-the-fly via `CentralMemory.resolveNoteMentions()`). Interactive `@` autocomplete is filtered to current-project notes and unfinished tasks.

### 5.4 Web UI Model & Server Config Modal (`🧠 Models`)
- **UI Controls:** Accessible via the floating `🧠` button next to `Send` and the header `🧠 Models` button, opening a 2-tab modal for Agent Models and Inference Servers.
- **REST Endpoints:** `/api/config/state`, `/api/config/server-models`, `/api/config/agent`, `/api/config/reset`, and `/api/config/servers`.

### 5.5 RabbitMQ & Built-In Internal Queue Worker Mode (`/mq` & Scripts)
- **Queue Modes:**
  - `RABBITMQ`: External AMQP RabbitMQ broker (`mkpro.tasks`, `mkpro.results`, `mkpro.tasks.dlq`).
  - `INTERNAL`: Zero-dependency in-memory queue + loopback HTTP bridge on `127.0.0.1:8765`.
  - `AUTO` (Default): Uses RabbitMQ if reachable, with automatic fallback to `INTERNAL`.
- **CLI Commands:** `/mq on [internal|rabbitmq|auto]`, `/mq off`, `/mq status`, `/mq config`, `/mq send <prompt>`, `/mq results [limit]`.
- **Scripts:**
  - `mkpro-worker.bat` / `mkpro-worker.sh` — Launch headless MQ worker mode (`--worker`).
  - `mkpro-mq-test.bat` / `mkpro-mq-test.sh` — Run end-to-end MQ verification via `MqTestClient` (e.g., `./mkpro-mq-test.sh --mode internal`).

## 6. Key Conventions & Rules
- **Git Operations:** **NEVER** commit or push changes unless explicitly authorized by the user. All VCS actions must remain strictly within the user's consent boundaries.
- **Surgical Editing & Backups:** Prefer `replace_in_file` for surgical modifications on large files (>300 lines) and `safe_write_file` for two-step diff previews with automatic backups.
- **Performance Strategy:** Implements **Prefix Caching** and **Session-Start Compaction** (`SmartEventFilterPlugin`) alongside token-aware `OpenAiBaseLM` truncation to maintain context window efficiency and minimize latency.
- **Security:**
  - **PathValidator:** All file system operations must pass through validation.
  - **CommandPolicy:** Strict enforcement via `RemoteCommandPolicy` and `RemotePathValidator` for all remote/system-level interactions.
- **Integration:** WebUI and MapDB (`CentralMemory`) act as the unified source of truth for task status, persistent memory, project-scoped configurations, and agent communication logs.
