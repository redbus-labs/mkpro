# Knowledge Scheduler — Autonomous Knowledge Accumulation

The Knowledge Scheduler is mkpro's self-improving knowledge engine. It autonomously fetches, analyzes, and builds evolving topic reports — getting smarter with every cycle.

## Core Concept

```
Timer fires → fetch sources → Markov routes to specialist agent → agent analyzes & merges
→ report stored in CentralMemory → TF-IDF indexed for search → training data exported
→ next startup: Markov router improves from scheduler interactions → better routing
```

Unlike simple monitoring ("alert if broken"), the Knowledge Scheduler is a **knowledge accumulator** — it builds and refines a persistent, searchable knowledge base that evolves over time.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    KnowledgeScheduler                         │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────────┐    │
│  │  Timer   │→ │ SourceFetcher│→ │  ADK Runner        │    │
│  │(per topic)│  │  (HTTP GET)  │  │  (runAsync)        │    │
│  └──────────┘  └──────────────┘  └────────┬───────────┘    │
│                                            │                 │
│                                   ┌────────▼───────────┐    │
│                                   │   Markov Router    │    │
│                                   │ (fast-routes to    │    │
│                                   │  specialist agent) │    │
│                                   └────────┬───────────┘    │
│                                            │                 │
│         ┌──────────────────────────────────▼──────────┐     │
│         │  Agent (SecurityAuditor / Architect / etc.)  │     │
│         │  Analyzes, merges, discovers sub-topics      │     │
│         └──────────────────────────────────┬──────────┘     │
│                                            │                 │
│  ┌─────────────┐  ┌───────────┐  ┌────────▼──────┐         │
│  │ TopicIndex  │← │ Knowledge │← │  TopicReport  │         │
│  │ (TF-IDF)   │  │   Store   │  │  (evolved)    │         │
│  └─────────────┘  └───────────┘  └───────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

## Components

| Component | File | Responsibility |
|---|---|---|
| `KnowledgeScheduler` | `knowledge/KnowledgeScheduler.java` | Timer loop, orchestrates fetch→analyze→store→index cycle |
| `TopicConfig` | `knowledge/TopicConfig.java` | YAML-mappable topic configuration |
| `TopicReport` | `knowledge/TopicReport.java` | Stored report with history, confidence, keywords |
| `TopicIndex` | `knowledge/TopicIndex.java` | TF-IDF bag-of-words search (cosine similarity) |
| `KnowledgeStore` | `knowledge/KnowledgeStore.java` | CentralMemory CRUD with `knowledge:` prefix |
| `SourceFetcher` | `knowledge/SourceFetcher.java` | HTTP GET with 30s timeout, 500KB cap |
| `KnowledgeCommand` | `commands/impl/KnowledgeCommand.java` | `/know` CLI command |

## Configuration

Create `schedules.yaml` in either location (searched in priority order):

1. `.mkpro/schedules.yaml` — project-local
2. `~/Documents/mkpro/schedules.yaml` — user-global

```yaml
topics:
  - name: kubernetes-security
    title: "Kubernetes Security Best Practices"
    sources:
      - "https://kubernetes.io/docs/concepts/security/"
      - "https://raw.githubusercontent.com/OWASP/CheatSheetSeries/master/cheatsheets/Kubernetes_Security_Cheat_Sheet.md"
    refreshIntervalMinutes: 120
    agent: SecurityAuditor
    instruction: >
      Analyze the fetched content about Kubernetes security. Focus on:
      - New CVEs or vulnerabilities mentioned
      - Best practices for pod security
      - Network policies and RBAC recommendations
      Merge with existing knowledge, note any changes.

  - name: java-performance
    title: "Java Performance & JVM Tuning"
    sources:
      - "https://docs.oracle.com/en/java/javase/21/gctuning/"
    refreshIntervalMinutes: 240
    agent: Architect
    instruction: >
      Extract and summarize Java 21 GC tuning best practices.
      Focus on G1GC and ZGC recommendations.
```

### Topic Fields

| Field | Required | Default | Description |
|---|---|---|---|
| `name` | ✓ | — | Unique identifier (lowercase, hyphenated) |
| `title` | — | Same as name | Human-readable title |
| `sources` | ✓ | — | List of URLs to fetch |
| `refreshIntervalMinutes` | — | 60 | How often to refresh |
| `agent` | — | Coordinator | Hint for which agent analyzes (Markov routes based on content) |
| `instruction` | — | — | Focus directive for the analyzing agent |

## Launch

```bash
# Knowledge scheduler active
java -jar mkpro-4.1.0.jar --scheduler

# With web UI (browse knowledge at http://localhost:8080/knowledge)
java -jar mkpro-4.1.0.jar --web --scheduler

# Scheduler is optional — /know search still works on existing data without it
java -jar mkpro-4.1.0.jar --web
```

## Commands

| Command | Description |
|---|---|
| `/know <query>` | Search all topics by TF-IDF similarity |
| `/know topics` | List all topics with summaries and confidence |
| `/know topic <name>` | Show full report, history, sources, keywords |
| `/know status` | Scheduler status: last refresh, access counts, pending discoveries |
| `/know refresh <name>` | Force immediate refresh of a topic |
| `/know refresh all` | Force refresh all topics |
| `/know approve <name>` | Promote a discovered sub-topic to scheduled |
| `/know dismiss <name>` | Discard a pending discovery |

## Self-Improving Mechanisms

### 1. Intelligent Merging (evolve, not replace)

Each refresh cycle, the agent receives:
- The **existing** report summary
- **New** fetched data
- Instructions to **merge** — not overwrite

The prompt explicitly asks to:
- Identify contradictions with existing knowledge
- Note trends and changes over time
- Keep the report concise but comprehensive
- Discover related sub-topics

Over time, each topic report becomes denser and more insightful — not just a log of fetches.

### 2. Confidence Scoring

```
Initial confidence: 50%

On successful refresh:
  - New data confirms existing (>80% similarity): +10%
  - Normal update: +5%
  - Contradicts existing (<30% similarity): -5%

Staleness decay:
  - -1% per day without refresh
  - Minimum: 10%
  - Applied every 24 hours by scheduled task
```

Confidence is visible in `/know topics` and the web UI. Low-confidence topics signal stale or contradictory data.

### 3. Topic Discovery

Agents can discover new topics during analysis. When the LLM detects a related area worth tracking separately, it outputs:

```
DISCOVER_TOPIC:jvm-gc-tuning:https://docs.oracle.com/gc-tuning-guide
```

These appear as **pending discoveries** in `/know status`. You approve or dismiss them:

```
/know approve jvm-gc-tuning    → creates new scheduled topic, refreshes in 10s
/know dismiss jvm-gc-tuning    → discarded
```

This prevents unbounded topic sprawl while letting the system suggest valuable expansions.

### 4. Access-Frequency Weighted Refresh

Topics you search for more get refreshed more often:

```
Effective interval = base_interval × (1 - accesses × 2%)
Minimum: 50% of base interval

Example:
  base = 120 min, 10 searches recorded
  effective = 120 × (1 - 0.20) = 96 min
```

The system adapts to what you actually care about. Unused topics stay at their configured interval.

### 5. Markov Router Training (the flywheel)

The Knowledge Scheduler generates training data as a side effect:

```
Scheduler runs → prompts flow through runner → ActionLogger records interactions
→ auto-export on exit as JSONL → /train on next startup
→ Markov router learns: "CVE + RBAC → SecurityAuditor at 90%"
→ next scheduler run: faster routing, better agent selection
```

This means:
- **Router improves** from scheduler interactions (more data points per session)
- **Maker learns** completion patterns for knowledge-type tasks
- **Stall prediction** learns which agents fail for which knowledge queries
- **Learned patterns** (TF-IDF bigrams) improve from scheduler vocabulary

The more topics you run, the smarter the entire system gets — for both human and automated queries.

## Web UI

Access at `http://localhost:8080/knowledge` (requires `--web` flag):

- **Topic cards** — name, title, confidence, keywords, summary preview
- **Detail panel** — full report, sources, history timeline
- **Search** — real-time TF-IDF search via `/api/knowledge/search?q=...`
- **Refresh** — manual reload

API endpoints:
- `GET /api/knowledge` — all topics as JSON
- `GET /api/knowledge/search?q=<query>` — TF-IDF search results

## Storage

| Data | Location | Format |
|---|---|---|
| Topic reports | CentralMemory (`knowledge:<name>`) | JSON via Jackson |
| TF-IDF index | In-memory (rebuilt on startup) | ConcurrentHashMap |
| Search vectors | In-memory (rebuilt from reports) | TF-IDF sparse vectors |
| Topic configs | `.mkpro/schedules.yaml` | YAML |
| Training data | `datajsonl/session_auto_*.jsonl` | JSONL (auto-exported) |

Reports persist across restarts via CentralMemory (MapDB shared store). The TF-IDF index is rebuilt on startup from existing reports — no separate index file needed.

## Data Flow Example

```
Day 1:
  Topic "k8s-security" created → first fetch → Coordinator → SecurityAuditor
  SecurityAuditor writes initial report (confidence: 55%)
  Indexed for search, exported as training data

Day 2:
  Timer fires → fetch same URLs → new CVE announced in fetched page
  SecurityAuditor: "MERGE: Added CVE-2026-1234 affecting pod security"
  Confidence: 60% (new info confirms + extends existing)
  Discovers sub-topic: DISCOVER_TOPIC:pod-security-standards:https://...

Day 3:
  You search: /know "pod security CVE"
  → TF-IDF finds k8s-security (92% match)
  → recordAccess("k8s-security") → effective interval drops from 120→108 min
  
  You approve: /know approve pod-security-standards
  → new topic scheduled, first refresh in 10s

Day 7:
  k8s-security refreshed 3x, confidence at 75%
  pod-security-standards at 60% after 2 refreshes
  Markov router now routes "CVE pod RBAC" → SecurityAuditor at 88% confidence
  (learned from 6+ scheduler interactions)
```

## Limits & Safety

- **Max response size**: 500KB per source fetch (truncated with note)
- **LLM timeout**: 120 seconds per analysis call
- **Prompt truncation**: New data capped at 8000 chars to prevent token overflow
- **No write actions**: Scheduler is fetch + analyze + report only
- **No concurrent edits**: Single daemon thread processes topics sequentially
- **Staleness floor**: Confidence never drops below 10%
- **Topic stagger**: 30s between topic start times to avoid API burst
- **Graceful failure**: One topic error doesn't stop others

## Future Improvements

- [ ] Use `agent` field to route directly via `runner.runAsync(agentName, sessionId, content)` for explicit agent targeting
- [ ] RSS feed support in SourceFetcher (parse `<item>` elements)
- [ ] Scheduled deletion of topics with 0 accesses and <20% confidence after 30 days
- [ ] Vector embeddings upgrade (replace TF-IDF with sentence embeddings when local model available)
- [ ] Multi-source diff detection (only trigger LLM if fetched content actually changed)
- [ ] Export knowledge base as Markdown wiki
