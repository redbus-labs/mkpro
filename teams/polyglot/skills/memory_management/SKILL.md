# Memory Management Skill

## Description
Durable key-value and architectural insight persistence across agent sessions. Available in both native Python (`.py`) and native Groovy (`.groovy`) implementations with atomic persistence and taxonomy tagging.

## Usage
Used by agents to record decisions, architectural insights, requirements lineage, and recall previously stored project context.

## Scripts

### 1. `commit_memory` (`commit_memory.py`, `commit_memory.groovy`)
Stores key project facts, architecture decisions, and operational context into persistent storage (`.mkpro/project_memory.json`).
- **Python CLI**: `python scripts/commit_memory.py <key> <content> [--tag <tag>] [--store <path>]`
- **Groovy CLI**: `groovy scripts/commit_memory.groovy <key> <content> [--tag <tag>] [--author <name>] [--store <path>]`
- **Groovy Binding**: `[key: "architecture", content: "Microservices design", tag: "architecture", author: "agent", store: ".mkpro/project_memory.json"]`
- **Output**: Structured Map/JSON containing `status`, `key`, `tag`, `timestamp`, `entries_count`, `store`.

### 2. `recall_memory` (`recall_memory.py`, `recall_memory.groovy`)
Retrieves stored facts, insights, and decisions by key, full-text filter, or category tag.
- **Python CLI**: `python scripts/recall_memory.py [--key <key>] [--filter <query>] [--all] [--store <path>]`
- **Groovy CLI**: `groovy scripts/recall_memory.groovy [--key <key>] [--filter <query>] [--tag <tag>] [--all] [--store <path>]`
- **Groovy Binding**: `[key: "architecture", filter: "design", tag: "architecture", all: false, store: ".mkpro/project_memory.json"]`
- **Output**: Structured Map/JSON containing `results_count`, `entries` map of matching records, `store`.

## Resources
- **`resources/memory_schema.md`**: Storage schema, key naming conventions, taxonomy tags, and retention policies.
