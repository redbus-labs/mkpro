# Codebase Search Skill

## Description
High-speed text, regex, and file pattern discovery tools across project codebases. Available in both native Python (`.py`) and native Groovy (`.groovy`) implementations with automatic filtering of build/vcs directories.

## Usage
Used by agents to locate symbols, class definitions, function calls, and relevant configuration files across the codebase.

## Scripts

### 1. `search_codebase` (`search_codebase.py`, `search_codebase.groovy`)
Searches file contents for keywords, tokens, or regex patterns with optional context lines before/after matches.
- **Python CLI**: `python scripts/search_codebase.py <query> [--path .] [--file-types .java,.py] [--regex] [--case-sensitive] [--max-results 50] [--json]`
- **Groovy CLI**: `groovy scripts/search_codebase.groovy <query> [--path .] [--file-types .groovy,.java] [--regex] [--case-sensitive] [--context 2] [--max-results 50] [--json]`
- **Groovy Binding**: `[query: "Pattern", path: ".", file_types: ".groovy,.java", regex: false, context: 2, max_results: 50]`
- **Output**: Structured Map/JSON containing `query`, `total`, and list of `matches` (`file`, `line`, `content`, `context_before`, `context_after`).

### 2. `find_files` (`find_files.py`, `find_files.groovy`)
Locates files and directories matching glob patterns with built-in exclusion filters (`.git`, `target`, `node_modules`, `build`, `.gradle`, `.idea`, `venv`, `__pycache__`, `.mkpro`).
- **Python CLI**: `python scripts/find_files.py --pattern "*Test*.java" [--path .] [--type file|dir|all] [--max-results 100]`
- **Groovy CLI**: `groovy scripts/find_files.groovy --pattern "*Test*.groovy" [--path .] [--type file|dir|all] [--exclude "dist,tmp"] [--max-results 100]`
- **Groovy Binding**: `[pattern: "*.groovy", path: ".", type: "file", max_results: 100]`
- **Output**: Structured Map/JSON containing `total`, `pattern`, and list of `results` (`name`, `path`, `type`, `size_bytes`).

## Resources
- **`resources/search_patterns.md`**: Common search queries, regex patterns, and filtering strategies for codebase exploration.
