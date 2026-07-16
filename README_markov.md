# Markov Chain Router — Inner Workings

The Markov Chain Router is a probabilistic model that learns to route user requests directly to the correct agent **without an LLM call**. It reduces latency and token costs by bypassing the Coordinator for predictable requests.

## Architecture Overview

```
User Input
    │
    ▼
┌───────────────────┐
│ IntentClassifier   │  Keyword/regex → TaskCategory
│ (14 categories)    │  Confidence: 0.0 - 1.0
└────────┬──────────┘
         │
         ▼ category + confidence
┌───────────────────┐
│ MarkovRouter       │  P(agent | category) from transition matrix
│ (transition matrix)│  Confidence threshold: 65%
└────────┬──────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
 ROUTE     FALLBACK
(≥ 65%)    (< 65%)
    │         │
    ▼         ▼
 Agent      Coordinator
directly    (LLM decides)
```

## Components

### 1. IntentClassifier (`com.mkpro.routing.IntentClassifier`)

Categorizes user input using keyword/regex pattern matching. No ML involved — pure rule-based.

**Categories (14):**

| Category | Example Keywords |
|---|---|
| CODING | implement, code, function, class, api, feature, bug, fix |
| TESTING | test, junit, pytest, coverage, e2e, selenium, verify |
| GIT | commit, push, pull, merge, branch, diff, tag |
| DEVOPS | docker, kubernetes, deploy, ci/cd, terraform, aws |
| SECURITY | vulnerab, audit, injection, xss, owasp, encrypt |
| ARCHITECTURE | architect, design, pattern, refactor, coupling, scalab |
| DATABASE | sql, migration, schema, postgres, mongodb, orm |
| DOCS | readme, document, javadoc, changelog, api doc |
| SYSADMIN | run, execute, build, install, process, environment |
| DATA | csv, pandas, numpy, statistics, visualization |
| ANDROID | android, kotlin, jetpack, compose, gradle android |
| IOS | ios, swift, swiftui, xcode, cocoapods |
| GOALS | goal, todo, task track, progress, sprint, backlog |
| GENERAL | (no match — fallback to Coordinator) |

**Confidence scoring:** `min(1.0, matches / 3.0)` — one keyword match = 33%, two = 67%, three+ = 100%.

**Threshold:** Intent confidence must be > 30% (at least one keyword match) to attempt routing.

**Learned Patterns (adaptive):** When static patterns return GENERAL, the classifier checks learned patterns extracted from training data:
- Unigrams: distinctive words per category (TF-IDF scored, ≥3 occurrences, top 20)
- Bigrams: 2-word phrases (prefixed `B:`, scored 2x weight)
- Minimum 2 points required to override GENERAL
- Patterns persist in `markov_model.dat` and improve each `/train` cycle

### 2. MarkovRouter (`com.mkpro.routing.MarkovRouter`)

A probabilistic state machine that predicts which agent should handle a given task category.

**The transition matrix:**

```
                    Coder  Tester  GitAgent  Architect  DevOps  ...
Category=CODING:    0.72   0.05    0.02      0.15       0.01   ...
Category=TESTING:   0.05   0.85    0.02      0.03       0.01   ...
Category=GIT:       0.02   0.01    0.91      0.01       0.01   ...
```

Each cell = `P(agent | category)` = count(category→agent) / total(category)

**Routing decision:**
```java
confidence = bestAgentCount / totalCountForCategory;
shouldRoute = confidence >= 0.65;  // Default threshold
```

**Order-1 Markov (optional):** Also considers the last agent used:
```
P(next_agent | category, last_agent)
```
Example: After Coder runs, there's a high probability CodeEditor is next (to apply changes).

**Confidence threshold:** Default 65%. Configurable. When confidence is below threshold, the request falls through to the LLM Coordinator.

### 3. MarkovTrainer (`com.mkpro.routing.MarkovTrainer`)

Builds the transition matrix from JSONL training data.

**Input format:** Standard chat fine-tuning JSONL:
```json
{"messages": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]}
```

**Extraction pipeline:**
1. Parse user message → classify with IntentClassifier → get TaskCategory
2. Parse assistant message → extract delegated agent via regex
3. Record transition: `recordTransition(category, lastAgent, selectedAgent)`

**Agent extraction regexes:**
```
"[Calling ask_coder with instruction..."   → captures "coder"
"I'll delegate to the Architect..."        → captures "Architect"
```

**Agent name normalization:** `ask_sys_admin` → `SysAdmin`, `ask_git_agent` → `GitAgent`, etc.

### 4. GenerateBundledModel (`com.mkpro.routing.GenerateBundledModel`)

Utility to pre-generate the bundled model from synthetic training data. Output is packaged in the JAR as a resource.

## Data Flow

### Training Pipeline

```
datajsonl/*.jsonl
    │
    ▼ MarkovTrainer.trainFromDirectory()
┌─────────────────┐
│ For each line:   │
│  1. Parse JSON   │
│  2. Classify     │
│  3. Extract agent│
│  4. Record       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ MarkovRouter     │
│  transitions:    │  category → lastAgent → {agent: count}
│  categoryToAgent:│  category → {agent: count}
│  totalObservations│
└────────┬────────┘
         │
         ▼ router.save()
  .mkpro/markov_model.dat (serialized Java objects)
```

### Runtime Routing

```
User types: "write unit tests for UserService"
    │
    ▼ IntentClassifier.classify()
Category = TESTING (confidence: 67% — "test" matched)
    │
    ▼ MarkovRouter.route(TESTING, null)
Lookup: categoryToAgent["TESTING"] = {Tester: 822, Coder: 45, SysAdmin: 12}
Best: Tester (822 / 879 = 93% confidence)
    │
    ▼ 93% >= 65% threshold
ROUTE DIRECTLY → Tester
    │
    ▼ Rewrite input
"Delegate to Tester: write unit tests for UserService"
    │
    ▼ Sent to Coordinator (which immediately delegates to Tester)
```

### Self-Improvement Cycle

```
┌─────────────────────────────────────────────────┐
│                  SESSION                          │
│                                                   │
│  1. Startup: load model + train from new JSONL   │
│  2. Runtime: fast-route + recordTransition()     │
│  3. Exit: auto-export logs + save model          │
│                                                   │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
              Next session startup
              (loads improved model)
```

**Live learning:** Every routing decision is recorded in real-time via `recordTransition()`. Even without re-training, the model improves during a session.

**Auto-export on exit:** Session logs are exported as `datajsonl/session_auto_*.jsonl`. Next startup trains on these automatically.

## Storage

| File | Location | Purpose |
|---|---|---|
| `markov_model.dat` | `.mkpro/` (project dir) | Serialized transition matrix (user's personalized model) |
| `markov_model_default.dat` | JAR resources | Bundled baseline model for fresh installs |
| `session_auto_*.jsonl` | `datajsonl/` | Auto-exported session data for re-training |
| `coordinator_training.jsonl` | `datajsonl/` | Synthetic training data (30K examples) |
| `coordinator_decisiveness.jsonl` | `datajsonl/` | Decisiveness-focused examples (10K) |

## Commands

| Command | Description |
|---|---|
| `/train` | Re-train model from all datajsonl/*.jsonl files |
| `/train status` | Show observations, threshold, category→agent mapping, learned patterns, transition matrix |
| `/train reset` | Delete model, retrain from scratch |
| `/train threshold <N>` | Set confidence threshold (10-99%) |

## Stall Prediction & Agent Redirect

The Maker tracks agent sequences that historically led to stalls. When a similar pattern is detected:

1. **Predict stall** — subsequence overlap ≥60% against known stall patterns
2. **Redirect** — `routeExcluding(triedAgents)` picks the next-best agent from the Markov matrix
3. **Max 2 redirects** — if all alternatives exhausted, wrap up via Coordinator summary
4. **Learn** — on every failed/escalated goal, the agent sequence is stored as a stall pattern

```
Coder stuck 4 turns → Redirect to Architect → Architect provides design → Coder finishes
```

## Configuration

**Confidence threshold:** Default 65%. The router only fast-routes when it's at least 65% confident. Lower = more aggressive routing (faster but may misroute). Higher = more conservative (falls back to LLM more often).

**Minimum observations:** Router won't activate until it has 20+ observations. Prevents routing on insufficient data.

**Intent confidence:** Must be > 30% (at least one keyword match). Pure conversational inputs ("hello", "thanks") always go to Coordinator.

## Why Markov Chains?

| Property | Benefit |
|---|---|
| Zero latency | No network call, no model inference — pure math |
| Zero cost | No tokens consumed for routing decisions |
| Interpretable | You can see exactly why it routed where (`/train status`) |
| Self-improving | Gets better with every session automatically |
| Bounded risk | Falls through to LLM when uncertain — worst case = same as before |
| Lightweight | ~10KB model file, microsecond predictions |

## Limitations

- **Memoryless:** Doesn't understand multi-turn context (only current input + last agent)
- **Keyword-dependent:** Novel vocabulary not in the pattern list won't classify
- **Category granularity:** 14 categories may not cover all possible intents
- **Cold start:** Needs training data to be useful (solved by bundled default model)

## Future Improvements

- Order-2 Markov: consider last 2 agents for better sequence prediction
- Semantic fallback: small ONNX embedding model for truly novel phrasing
- Feedback loop: detect when Coordinator overrides the Markov decision and learn from it
- Per-project pattern isolation: "compose" means Android in one project, Docker in another
- Confidence blending: weighted sum of static + learned + history signals
