# mkpro v4.1.0 Release Notes

**Release Date:** July 2026  
**Commits:** 20+ since v4.0.0  
**Tests:** 388 passing, 0 failures

---

## 🚀 Highlights

- **Knowledge Scheduler** — autonomous, self-improving knowledge accumulator
- **Event Bus Architecture** — decoupled event-driven output for terminal, web, and REST
- **Web UI Overhaul** — file browser, preview panel, diff viewer, dark/light themes
- **HTTP REST API** — full programmatic access (sync chat, SSE streaming, commands)
- **Unified Write Approval** — all file writes show diff + require approval across all channels
- **Multi-Format File Reader** — PDF, DOCX, XLSX, PPTX, SVG, DXF, STL, OBJ
- **Fully Dynamic YAML Agents** — create new agents without touching code
- **NVIDIA Provider** — NvidiaBaseLM support added

---

## 🧠 Knowledge Scheduler

An autonomous topic-based knowledge engine that fetches, analyzes, and evolves topic reports.

- **Phase 1:** Fetch sources → LLM analysis → store → TF-IDF indexed search
- **Phase 2:** Intelligent merging (evolve, not replace), topic discovery, confidence scoring, staleness decay, access-frequency weighted refresh
- **`request_knowledge` tool** — agents signal knowledge gaps, auto-approved with priority boost
- **Circular dependency prevention** — ThreadLocal context flag blocks recursive requests
- **Commands:** `/know <query>`, `/know topics`, `/know topic <name>`, `/know status`, `/know refresh`, `/know approve`, `/know dismiss`
- **Config:** `schedules.yaml` (project-local or user-global), template auto-copied on first run
- **Web UI:** `/knowledge` dashboard with topic cards, search, detail panel
- **Training flywheel:** scheduler interactions auto-exported → improve Markov router next startup

See [README_knowledge.md](README_knowledge.md) for full documentation.

---

## 🔌 Event Bus Architecture

Decoupled event-driven output replaces direct `System.out` and `WebSocket.broadcast` calls.

- **MkProEvent** — 17 event types (routing, maker, streaming, delegation, edit proposals, system)
- **MkProEventBus** — CopyOnWriteArrayList, synchronous dispatch, exception-safe
- **TerminalSink** — ANSI-formatted CLI output
- **WebSocketSink** — JSON broadcast to web clients
- Adding a new output channel = implement `MkProEventListener`, register on bus. Zero changes to producers.
- All streaming (start/chunk/end), routing decisions, Maker thoughts flow through the bus

---

## 🌐 Web UI

### New Features
- **Project File Browser** (left panel) — tree view, expandable folders, lazy-loaded, language-specific icons
- **Tabbed Preview Panel** — code (syntax highlighted), images, SVG, PDF (iframe), 3D models (three.js)
- **Resizable Dividers** — drag handles between all panels with min/max constraints
- **Dark/Light Theme** — 🌙/☀️ toggle, persisted in localStorage, switches highlight.js theme too
- **Loading Indicator** — animated dots while waiting for response
- **Timestamps** — HH:MM on all messages
- **File Attachments** — 📎 button, drag/drop, shown as chips
- **Screenshot to Chat** — 📸 captures preview panel as PNG, attaches as image for vision analysis
- **Scroll-to-Load History** — scroll up to load older messages from ActionLogger
- **Diff Viewer** — edit proposals shown with colored diff, approve/reject buttons, auto-approve countdown
- **Markov Routing Events** — routing/maker bubbles now visible in web (was CLI-only before)

### New Pages
- `/knowledge` — Knowledge base dashboard
- `/db` — MapDB store browser (all stores, search/filter)

---

## 🌐 HTTP REST API

Full programmatic access alongside the WebSocket chat:

| Endpoint | Description |
|---|---|
| `POST /api/chat` | Synchronous chat (blocks until complete) |
| `POST /api/chat/stream` | Server-Sent Events streaming |
| `POST /api/command` | Execute CLI commands |
| `GET /api/status` | System info |
| `GET /api/agents` | All agents with configs |
| `GET /api/knowledge` | Knowledge topics |
| `GET /api/knowledge/search?q=` | TF-IDF search |
| `GET /api/files?path=` | Directory listing |
| `GET /api/file-content?path=` | File text (10KB cap) |
| `GET /api/file-raw?path=` | Raw binary with MIME type |
| `GET /api/db` | MapDB store browser |
| `GET /api/history?offset=&limit=` | Paginated chat history |
| `POST /api/edit/approve` | Approve pending edit |
| `POST /api/edit/reject` | Reject pending edit |
| `GET /api/edit/pending` | List pending edits |

---

## ✏️ Unified Write Approval

All file writes (`write_file` + `safe_write_file`) now flow through `EditApprovalService`:

- Diff computed (old vs new content)
- `EDIT_PROPOSAL` event emitted → visible in terminal AND web simultaneously
- **Terminal:** shows diff + 7s auto-approve timer
- **Web:** diff viewer with Approve/Reject buttons + countdown
- **REST:** `POST /api/edit/approve|reject`
- All race on the same `CompletableFuture` — first response wins
- Backup (`Maker.backItUp`) created before every approved write
- 30s timeout → auto-approve (headless safety)

---

## 📄 Multi-Format File Reader

`read_file` tool auto-detects and extracts text from:

| Format | What's Extracted |
|---|---|
| PDF | Text by page; image-based pages rendered as PNG for vision |
| DOCX | Paragraphs + tables |
| XLSX | Sheets as tab-separated rows |
| PPTX | Slides as numbered text blocks |
| SVG | Text elements, dimensions, shape counts |
| DXF | Layers, annotations, dimension values |
| STL | Facet count, bounding box, dimensions (ASCII + binary) |
| OBJ | Vertices, faces, materials, groups, bounding box |

Dependencies: Apache PDFBox 3.0.3, Apache POI 5.3.0

---

## 🤖 Fully Dynamic YAML Agents

New agents can now be created purely in YAML — no code changes required:

```yaml
- name: MyCustomAgent
  description: "Does something specialized"
  instruction: |
    Your role and responsibilities...
  tools: [file_read, shell, fetch_url, request_knowledge]
  needs_context: true
  routing_keywords: [keyword1, keyword2, "multi word"]
  fallback_model: gemini-2.0-flash@GEMINI
```

What happens automatically:
- Agent loaded, config stored, `ask_<name>` delegation tool created
- `needs_context` controls project file injection (replaces hardcoded set)
- `routing_keywords` registered with IntentClassifier for fast-routing
- Configurable via `/config`, shows in `/status`

---

## 🎮 NVIDIA Provider

- Added `NVIDIA` to Provider enum
- `NvidiaBaseLM` wired in AgentManager
- Selectable via `/config` interactive menu
- ADK version updated to 1.6.1-SNAPSHOT

---

## 🏗️ Build & Infrastructure

- **Maven auto-pack:** `teams/` folder auto-copied into JAR (no manual `Copy-Item`)
- **schedules_template.yaml:** bundled in JAR, copied to `.mkpro/` on first `--scheduler` run
- **Launch scripts:** `mkpro-web.bat/.sh`, `mkpro-scheduler.bat/.sh`, `mkpro-headless.bat/.sh`
- **Headless mode:** `--runner MAP_DB --web --scheduler` — zero interactive prompts
- **datajsonl/** removed from git tracking (runtime data, gitignored)
- **teams/** now tracked in git

---

## 🐛 Bug Fixes

- Fixed `BasicProcessExecutorTest` — timeout uses `ping` instead of interactive `timeout /t`, truncation handles CommandPolicy blocking
- Restored accidentally deleted `ShellExecutor.java` and `MkProTools.java`
- Removed accidentally committed mTLS files from another project
- Fixed ADK version alignment (1.6.1-SNAPSHOT matches local source)
- Web input now runs full Markov routing (was bypassing entirely — only sent to Coordinator)

---

## 📚 Documentation

- [README_knowledge.md](README_knowledge.md) — Full Knowledge Scheduler docs
- [README_markov.md](README_markov.md) — Markov Chain Router docs
- README.md updated with REST API, launch scripts, all new features

---

## 📊 Stats

- **Tests:** 388 passing (+12 from 4.0.0)
- **New files:** ~30 Java source files, 4 HTML/web files
- **New packages:** `com.mkpro.events`, `com.mkpro.knowledge`
- **Net lines added:** ~6,000+
