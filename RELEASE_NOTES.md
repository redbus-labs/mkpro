# MkPro v5.0.0 Release Notes

**Release Type:** Major Release (`v5.0.0`)

---

## 🌟 Release Overview: MkPro 5.0.0 Major Release

We are thrilled to announce **MkPro v5.0.0**, a major milestone release that elevates `mkpro` into a self-verifying, concurrent, and deeply observable AI software engineering platform.

**MkPro 5.0.0** introduces an **Autonomous Self-Healing Verification Gate** (`AutoVerifyGate` & `/verify`), **Batch Diff Review & One-Click Turn Rollback**, an **Incremental Codebase Symbol Graph** (`SymbolIndexTool`), **Live Agent Telemetry & Cost/Latency Breakdown** (`📊 Telemetry`), **Parallel Sub-Agent Fan-Out** (`ask_parallel_agents`), **Persistent Scheduled & Event-Driven Background Jobs** (`🐇 Jobs` & `JobScheduleManager`), **Auto-Updating `@[Task]` Lifecycle**, rich **LaTeX/KaTeX & Mermaid Diagram Rendering** across the Academic and Classic Web UIs, **Multi-Session Project Management**, and critical **Core Reliability & Provider Fixes** including native Gemini `/compact` context compaction.

---

## 🚀 Major Features & Architecture Enhancements

### 1. 🛡️ Autonomous Self-Healing Verification Gate (`AutoVerifyGate` & `/verify`)

`mkpro` now automatically validates source code changes immediately after any code-editing turn and triggers an autonomous self-healing repair loop when compilation or syntax errors are detected.

- **Multi-Language Compiler & Syntax Detection (`AutoVerifyGate`)**:
  - **Java / Maven**: Automatically runs `mvn test-compile -q` (detecting `pom.xml` or `mkpro/pom.xml`) or falls back to `javac` for standalone `.java` files.
  - **Python**: Validates modified `.py` files via `python -m py_compile <file>` (with `python3` fallback).
  - **Go**: Runs `go build ./...` across modified Go packages.
  - **TypeScript / Node.js**: Executes `tsc --noEmit` (when `tsconfig.json` is present) for `.ts`/`.tsx` files and `node --check <file>` for `.js` files.
- **Autonomous Self-Healing Repair Loops**:
  - Integrated across the **JLine CLI (`TerminalUI`)**, **WebSocket Streaming Chat (`WebChatServer`)**, and **REST Chat APIs (`/api/chat` & `/api/chat/stream`)**.
  - When verification fails after a turn that modified files, `mkpro` captures the compiler/syntax error output and automatically dispatches a self-healing follow-up turn to the active runner so the agent can fix the regression immediately.
- **Interactive `/verify` CLI Command & REST Endpoint (`/api/verify`)**:
  - `/verify on` | `/verify off` — Enable or disable automatic post-turn verification.
  - `/verify status` — Inspect current gate state (`ON`/`OFF`) and the last verification result summary (`PASSED`/`FAILED`).
  - `/verify run` — Manually trigger verification on files modified in the current turn (or the project build).
  - `GET / POST /api/verify` — Query verification status, toggle `autoVerify`, or trigger an on-demand verification check from the Web UI.

---

### 2. ⏪ Batch Diff Review & One-Click Turn Rollback

Review every file modified during an AI turn and revert individual files or entire turns with a single click.

- **Turn-Level File Edit Snapshots (`EditApprovalService`)**:
  - `EditApprovalService.beginTurn()` captures pre-turn baseline snapshots (`TurnFileSnapshot`) before the first modification to any file in a turn, tracking whether the file previously existed, its original content, line additions (`+`), line deletions (`-`), and modification count.
- **Granular & Batch Rollback APIs**:
  - `GET /api/edit/turn-summary` — Returns a complete summary of all files created or modified in the latest turn, including line delta counts (`addedLines`, `removedLines`), before/after content previews, and latest `AutoVerifyGate` status.
  - `POST /api/edit/revert-turn` — Atomically restores all files modified during the turn back to their pre-turn state (and deletes newly created files), invalidating stale symbol cache entries.
  - `POST /api/edit/revert` — Reverts a single specified file (`{"path": "..."}`) back to its pre-turn snapshot without losing other edits from the turn.
- **Floating Turn Change Summary Bar in Web UI**:
  - Both the **Academic Research View (`/`)** and **Classic View (`/classic`)** display a non-intrusive floating **Turn Change Summary Bar** above the chat composer whenever files are edited in a turn.
  - Shows total modified file count, `+added / -removed` line badges, auto-verify status badge, an interactive **Batch Diff Review Modal** (side-by-side / unified diff inspection per file with individual **Revert File** buttons), and a **Revert All (`⏪ Revert Turn`)** action.

---

### 3. 🧭 Incremental Codebase Symbol Graph (`SymbolIndexTool`)

Fast, zero-dependency AST regex symbol indexing and cross-reference lookup across polyglot repositories.

- **Multi-Language Symbol Extraction (`SymbolIndexTool`)**:
  - Indexes classes, interfaces, enums, records, structs, functions, and methods across **Java (`.java`)**, **Python (`.py`)**, **TypeScript/JavaScript (`.ts`, `.js`)**, and **Go (`.go`)**.
- **Three Declarative Agent Tools (`symbol_index` / `find_symbol` / `find_usages`)**:
  - `find_symbol`: Searches symbol definitions by exact or substring match, optional `kind` filter (`class`, `interface`, `enum`, `record`, `struct`, `method`, `function`), and optional `path_prefix`, returning file paths, 1-indexed line numbers, and signatures.
  - `find_usages`: Locates all word-boundary references and call sites of a symbol across the codebase with surrounding line context, distinguishing definitions from usages.
  - `symbol_index`: Combined entry point supporting `action="find_symbol"`, `action="find_usages"`, or `action="stats"` for index health diagnostics.
- **Incremental `lastModifiedTime` Cache & Automatic Invalidation**:
  - Maintains a thread-safe in-memory `ConcurrentHashMap<Path, CachedFileSymbols>` keyed by normalized file path and last-modified timestamp so unchanged files are never re-parsed.
  - Automatically invalidates cached file entries whenever files are modified via `write_file`, `safe_write_file`, `replace_in_file`, or reverted via `EditApprovalService`.

---

### 4. 📊 Live Agent Telemetry & Cost/Latency Breakdown (`/api/telemetry`)

Real-time observability into token consumption, response latency, call volume, and estimated USD cost across every specialist agent and LLM provider.

- **Comprehensive Telemetry Aggregation (`GET /api/telemetry`)**:
  - Aggregates live session metrics and historical `ActionLog` records per agent and per model (`provider`, `modelName`, `calls`, `inputTokens`, `outputTokens`, `totalTokens`, `totalDurationMs`, `avgLatencyMs`, `estimatedCostUsd`).
  - Built-in pricing estimator calculates USD cost across cloud and local providers (Gemini, OpenAI, Bedrock/Claude, Azure, Sarvam, and zero-cost local inference on Ollama, vLLM, and Jlama).
  - Includes real-time `SmartEventFilterPlugin` context compaction savings (filtered events, pruned tool outputs, and estimated tokens saved).
- **Interactive `📊 Telemetry` Modal in Web UI**:
  - Accessible directly from the header bar (`📊 Telemetry`) in both Academic and Classic views.
  - Displays top-level KPI cards (**Total Calls**, **Total Tokens**, **Avg Latency**, **Estimated Session Cost USD**) and a sortable per-agent / per-model breakdown table with token distribution bars.

---

### 5. ⚡ Parallel Sub-Agent Fan-Out (`ask_parallel_agents`)

Concurrent multi-agent execution enabling the `Coordinator` to fan out independent subtasks to multiple specialists simultaneously and synthesize their results.

- **Concurrent Fan-Out / Fan-In Tool (`ask_parallel_agents`)**:
  - Accepts a JSON array of parallel task specifications (`[{"agent": "SecurityAuditor", "task": "..."}, {"agent": "Tester", "task": "..."}, ...]`).
  - Executes up to **6 specialist sub-agents concurrently** on a bounded virtual/cached thread pool with a per-task timeout safeguard (180 seconds) and recursion depth protection (`MAX_DELEGATION_DEPTH`).
  - Automatically forwards multimodal attachments (images, audio, video) from the parent session to each participating sub-agent and aggregates all specialist outputs into a structured fan-in report for the `Coordinator`.
- **Peer Agent Consultation (`ask_peer_agent` & ` schedule_background_job`)**:
  - Specialist agents can consult peers directly via `ask_peer_agent` or enqueue asynchronous follow-up tasks via `schedule_background_job`.

---

### 6. 🐇 Live Queue, Background Worker & Scheduled/Event-Driven Jobs (`🐇 Jobs` & `JobScheduleManager`)

Full lifecycle management for asynchronous background workers, recurring interval schedules, and file-edit-triggered automation.

- **Persistent Scheduled & Event-Driven Jobs (`JobScheduleManager`)**:
  - **Interval-Triggered Jobs**: Run recurring background prompts (e.g., periodic static analysis, doc sync, or test suite checks) at configurable intervals (`intervalSeconds >= 10`).
  - **`onEdit` Event-Driven Jobs**: Automatically trigger background jobs whenever files are modified in a turn (`triggerOnEdit = true`), with built-in 15-second debounce protection to avoid queue storms during rapid editing.
  - **Project-Scoped Persistence**: All job definitions, run counts, and last-run timestamps are persisted per project in `CentralMemory` (`<projectPrefix>mq:schedules`) and automatically restored on startup.
  - **Auto-Start Worker Safeguard**: When a scheduled or `onEdit` job fires (or a job is submitted via `/api/mq/send`), `JobScheduleManager` automatically starts `RabbitMqWorkerService` in `AUTO` mode if it is not already running.
- **Web UI `🐇 Jobs` Drawer & Modal**:
  - Manage the MQ Worker (`ONLINE`/`OFFLINE`, `AUTO`/`INTERNAL`/`RABBITMQ` modes), inspect live queue depths (`pending`, `results`, `dlq`), dispatch one-off background tasks, create/toggle/delete recurring or `onEdit` scheduled jobs, and inspect recent worker execution results in real time.
- **Backing REST Endpoints**:
  - `GET /api/mq/status`, `POST /api/mq/control`, `POST /api/mq/send`, `GET /api/mq/results`, and `GET | POST | DELETE /api/mq/schedules`.

---

### 7. ✅ Auto-Updating `@[Task]` Lifecycle & Multi-Session Management

Seamless task state transitions and multi-session workspace organization.

- **Automatic `@[Task]` State Transitions (`TODO` → `IN_PROGRESS` → `DONE`)**:
  - Referencing a project task in a prompt via `@[Task: Title]`, `@slug`, or `@id` automatically transitions the task from `TODO` to `IN_PROGRESS` when the turn starts (`CentralMemory.resolveNoteMentions()`).
  - Upon successful completion of the agent turn, `CentralMemory.markTasksDone()` automatically transitions referenced tasks to `DONE` and refreshes the Web UI `📌 Notes & Tasks` drawer in real time.
- **Multiple Named Sessions per Project (`/session` & Web UI Session Switcher)**:
  - Create, switch, rename, and delete multiple named conversation sessions per project via `/session list`, `/session new [name]`, `/session switch <id|name>`, `/session rename <name>`, `/session delete <id>`, or the Web UI session selector dropdown (`/api/sessions/*`).
  - Each session maintains isolated runner state and per-session Web UI chat history (`CentralMemory`), while dynamically updating the browser document title to reflect the active project and session.

---

### 8. 🎓 Academic & Classic Web UI Enhancements

Publication-grade mathematical typesetting, resilient diagram visualization, and responsive layout ergonomics.

- **Full LaTeX / KaTeX Math & Matrix Rendering**:
  - Integrated client-side LaTeX math typesetting across both the **Academic Research View (`/`)** and **Classic View (`/classic`)**.
  - Supports inline math (`$...$` and `\(...\)`), display math blocks (`$$...$$` and `\[...\]`), and standalone LaTeX environments (`\begin{pmatrix}...\end{pmatrix}`, `bmatrix`, `vmatrix`, `align`, `aligned`, `equation`, `cases`, `gather`) while protecting code blocks from false-positive math parsing.
- **Automatic Mermaid Flowchart Detection, Syntax Sanitization & Visual Fallback**:
  - Automatically detects both explicit ```` ```mermaid ```` / ```` ```flowchart ```` blocks and untagged code blocks starting with Mermaid diagram headers (`graph TD`, `flowchart LR`, `sequenceDiagram`, `classDiagram`, `stateDiagram`, `erDiagram`, `gantt`, `pie`, `mindmap`, etc.).
  - Sanitizes common LLM Mermaid syntax pitfalls (unquoted node labels containing parentheses, brackets, colons, slashes, or HTML entities; malformed arrow tokens; and trailing semicolons) before rendering.
  - Includes a built-in **Visual Flowchart Fallback Renderer** that parses edges and renders a clean interactive SVG/HTML node-edge diagram alongside a collapsible raw source view if Mermaid syntax remains invalid.
- **Dynamic Floating Dock Clearance (`--dock-safe-offset` & `ResizeObserver`)**:
  - Uses a `ResizeObserver` on `.floating-dock` in `academic_view.html` to dynamically compute `--dock-safe-offset` and adjust `#chat-container` bottom padding, preventing the floating dock and Turn Change Summary Bar from overlapping chat messages or action buttons.
- **Hot-Reload Static Asset Serving**:
  - `WebChatServer` now resolves static web assets (`academic_view.html`, `index.html`, `db.html`, `knowledge.html`) from disk (`src/main/resources/web/` or `mkpro/src/main/resources/web/`) before falling back to the packaged classpath resource, enabling instant UI iteration without rebuilding the JAR.

---

### 9. 🔧 Core Reliability & Provider Fixes

- **Unified `Coordinator – Coder` Pair Restoration & Markov Routing Consolidation**:
  - Restored clean `Coordinator` and `Coder` team configurations in `teams/default.yaml` and `ToolRegistry`, ensuring `Coordinator` delegates coding tasks cleanly while `Coder` retains the full suite of file manipulation, `replace_in_file`, `symbol_index`, `codebase_search`, `graph_memory`, and scripting tools.
  - Consolidated legacy `CodeEditor` Markov transitions into `Coder` and anchored agent keyword boundaries in `FastRouteEvaluator` and `IntentClassifier` to eliminate false-positive substring routing matches.
- **Native Gemini Provider `/compact` Context Compaction Fix**:
  - Fixed a critical `400 Bad Request` / orphaned function response issue when running `/compact` with the native **GEMINI** provider.
  - `SmartEventFilterPlugin` now groups `FunctionCall` (model role) and `FunctionResponse` (user role) event pairs as atomic units within the same invocation turn, preserves Gemini `thought` and `thoughtSignature` metadata on compacted/pruned parts in `ToolOutputPruner`, and rebuilds the active runner session cleanly after `/compact`.
- **Thread-Safe Stdout Capture, WebSocket Sink Lifecycle Cleanup & CRLF Normalization**:
  - Fixed concurrent stdout capture races during command execution (`RestApiHandler` / `WebChatServer`).
  - Added proper WebSocket sink unregistration and resource cleanup when executing `/web off` (`WebCommand`).
  - Normalized `CRLF` / `LF` line endings in `replace_in_file` (`MkProTools`) so surgical multi-line replacements succeed reliably across Windows, macOS, and Linux workspaces.

---

## 🌐 New & Updated REST API Endpoints in v5.0.0

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/telemetry` | Live per-agent and per-model token usage, latency, call counts, compaction savings, and estimated USD cost |
| `GET` | `/api/edit/turn-summary` | Summary of all files modified/created in the latest turn with line deltas and auto-verify status |
| `POST` | `/api/edit/revert-turn` | One-click rollback of all file modifications made in the latest turn |
| `POST` | `/api/edit/revert` | Revert a single specified file (`{"path": "..."}`) to its pre-turn snapshot |
| `GET / POST` | `/api/verify` | Query `AutoVerifyGate` status, toggle `autoVerify`, or trigger an immediate verification run |
| `GET` | `/api/mq/status` | Live queue worker status, active mode (`AUTO`/`INTERNAL`/`RABBITMQ`), and queue depths |
| `POST` | `/api/mq/control` | Start or stop the background queue worker (`action: "start" \| "stop"`, `mode`) |
| `POST` | `/api/mq/send` | Enqueue an asynchronous task prompt for background worker execution |
| `GET` | `/api/mq/results` | Fetch recent completed/failed background worker task results |
| `GET / POST / DELETE` | `/api/mq/schedules` | List, create, toggle, trigger, or delete recurring interval and `onEdit` background job schedules |
| `GET / POST` | `/api/sessions` | List project sessions or create/switch/rename/delete named sessions (`/api/sessions/new`, `/switch`, `/rename`, `/delete`) |

---

## 📦 Upgrade Notes

- **Java 21+ Required**: No breaking changes to existing `config.properties` or MapDB stores.
- **Automatic Schema & Store Migration**: Existing project sessions, sticky notes, tasks, and model configurations in `CentralMemory` upgrade seamlessly on startup.
- **Recommended Launch**:
  - Interactive CLI + Web UI: `./mkpro-web.sh` (Linux/macOS) or `mkpro-web.bat` (Windows)
  - Full Stack (Web + Scheduler + Worker): `./mkpro-full.sh` or `mkpro-full.bat`
