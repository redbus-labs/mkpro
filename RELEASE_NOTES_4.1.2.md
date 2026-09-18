# mkpro v4.1.2 Release Notes

**Released:** August 5, 2026

## Highlights

**Jlama Integration** — Pure Java LLM inference, no external dependencies. Download and run models directly in the JVM without Ollama or cloud APIs.

**Fact Engine** — Verified math formulas, relationship graph, and project fact discovery. The system learns your project's architecture and injects relevant facts into every conversation.

**Knowledge → Fact Pipeline** — Knowledge Scheduler now feeds extracted relationships and formulas into the Fact Engine graph. The system gets smarter with every knowledge refresh cycle.

---

## New Features

### Jlama Provider (Pure Java LLM)
- New provider: `JLAMA` — runs LLM inference in the same JVM process
- `/jlama download <model>` — pull models from HuggingFace
- `/jlama list` — show downloaded models with sizes
- `/jlama rm <model>` — remove local models
- `/jlama models` — recommended pre-quantized models
- `/config <agent> <model>@jlama` — assign Jlama models to agents
- Shared model cache (same model reused across multiple agents)
- Context-aware prompt truncation (auto-fits to model's context window)
- Streaming and blocking generation modes
- `jlama-download.bat` — standalone model downloader script
- Supports: Llama 3.x, Gemma 2, Qwen 2.5, Mistral, Yi-Coder, Granite, Mixtral

### Fact Engine
- 77 verified math formulas with Groovy verification scripts
- 151+ relationship triples (Kubernetes, Java, networking, databases, DevOps, security, cloud, etc.)
- `/facts` command suite: `math`, `rels`, `project`, `verify`, `check`, `query`
- `verify_fact` agent tool — agents can compute formulas or validate relationships mid-task
- Confidence-scored edges: 1.0 (static YAML), 0.9 (project), 0.85 (agent-discovered), 0.8 (extracted)
- Pre-turn fact injection: relevant formulas/relationships injected into agent context
- Post-turn validation: detects relationship conflicts in agent responses
- Project fact search: queries from user matched against discovered project facts
- Stop words configurable in `facts.yaml`

### Project Fact Discovery
- `/index` — scans source for constants, dependencies, config constraints
- `/index --deep` — LLM-assisted analysis of key project files
- LLM picks important files from project tree (no hardcoded language rules)
- Discovers: technology relationships, architectural patterns, formulas, thresholds
- Facts persist to MapDB across sessions (no re-indexing needed on restart)
- Project-scoped storage (facts from project A don't leak into project B)
- `.gitignore`-aware recursive scanning

### Knowledge → Fact Pipeline
- FactExtractor: extracts S-P-O triples from Knowledge Scheduler summaries
- Extracted math formulas with optional Groovy verification scripts
- Feeds into Fact Engine graph at 0.8 confidence
- Unified `/know` query shows both text results + relationship graph entries

### Layer 2 Markov (Agent→Tool Transitions)
- Tool transition probability matrix per agent
- Pre-delegation tool hints based on learned patterns
- Anomaly detection: +25% stall boost when unusual tools detected
- `/train status` shows Layer 2 statistics
- Corruption-safe model persistence (MAGIC header, CRC32, atomic write, .bak backup)
- Periodic mid-session save (every 10 turns)

### Maker as Knowledge Adequacy Supervisor
- Pre-goal: checks TopicIndex coverage → acquires knowledge if gap detected
- Post-turn reactive: detects uncertain responses → schedules knowledge → forces RETRY
- Post-goal retrospective: correlates success/failure with knowledge availability
- StreamKnowledgeMonitor: mid-stream gap detection from agent responses

---

## Improvements

### /index --deep Overhaul
- File tree cap: 500 → 1000 files
- Traversal depth: 10 → 12 levels
- Always skips: `node_modules`, `.git`, `vendor`, `target`, `build`, `dist`, `__pycache__`, etc.
- Filters binary files: `.png`, `.jar`, `.class`, `.lock`, `.min.js`, etc.
- Root-level files sorted first (configs/manifests visible to LLM even if truncated)
- LLM prompt budget doubled: 6000 → 12000 chars
- Better fuzzy path matching (handles `./` prefix, case differences, partial paths)
- Visible logging: shows file count and which files were selected

### Code Refactoring
- WebChatServer: 1380 → 375 lines (extracted RestApiHandler)
- MakerLoop: 785 → 505 lines (extracted KnowledgeAdequacyChecker)
- MarkovRouter: 680 → 627 lines (extracted ToolTransitionModel)
- BootstrapService: 750 → 586 lines (extracted ShutdownHandler)
- `AnsiColors.java`: single source of truth for all ANSI constants (14 files updated)
- `StoreKeys.java`: constants for CentralMemory keys
- Training data moved to `.mkpro/datajsonl/`

---

## Bug Fixes

- **Fact injection false positives**: Requires 2+ keyword matches (was 1)
- **Groovy evaluator blocking**: Per-evaluation daemon thread with 5s timeout (no permanent blocking)
- **Pre-goal knowledge check**: Now non-blocking (removed 45-second blocking wait)
- **Thread safety**: CopyOnWriteArrayList for RelationshipGraph edges
- **TerminalSink**: Now handles SYSTEM events (were silently dropped)
- **Partial key match**: `/facts verify circle_area` finds `geometry.circle_area`
- **GitIgnoreFilter**: Root directory always allowed (was being filtered)
- **Project fact leakage**: Facts scoped by project directory hash (no cross-project contamination)
- **Jlama prompt overflow**: Auto-truncates when exceeding model's context window

---

## Infrastructure

- JVM launch scripts updated with `--add-modules jdk.incubator.vector`
- Bundled Markov model regenerated: 18,721 observations from 56 JSONL files
- `jlama-download.bat` for standalone model downloads
- 105 new tests added (total: **552 tests, 0 failures**)

---

## Configuration

### Using Jlama
```bash
# Download a model
/jlama download tjake/Llama-3.2-3B-Instruct-JQ4

# Assign to an agent
/config Coder tjake/Llama-3.2-3B-Instruct-JQ4@jlama
```

### Fact Engine
```bash
# Scan project for facts
/index
/index --deep

# Browse facts
/facts
/facts project
/facts verify circle_area r=5
/facts check HPA requires metrics-server
```

---

## Upgrade Notes

- **Java 21+ required** (unchanged)
- **JVM flag recommended**: `--add-modules jdk.incubator.vector` (for Jlama SIMD acceleration)
- Jlama models stored at `~/Documents/mkpro/jlama-models/`
- Project facts persist automatically — existing sessions will load new fact data on restart
- No breaking changes to existing providers or commands
