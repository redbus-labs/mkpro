# Git Operations Skill

## Description
Comprehensive Git version control operations: status analysis, diff inspection, log history, and semantic commits with PII sanitization and secret leakage prevention. Available in both native Python (`.py`) and native Groovy (`.groovy`) implementations.

## Usage
Used by agents to inspect repository state, generate diffs, review revision history, and create standardized git commits.

## Scripts

### 1. `git_status` (`git_status.py`, `git_status.groovy`)
Inspects current branch, tracking branch, staged modifications, unstaged modifications, and untracked files.
- **Python CLI**: `python scripts/git_status.py [--repo-path .]`
- **Groovy CLI**: `groovy scripts/git_status.groovy [--repo-path .] [--json]`
- **Groovy Binding**: `[repo_path: "."]`
- **Output**: Structured Map/JSON containing `branch`, `clean`, `staged`, `unstaged`, `untracked`, `stats` (`staged_count`, `unstaged_count`, `untracked_count`).

### 2. `git_diff` (`git_diff.py`, `git_diff.groovy`)
Displays unified diffs and stat summaries for staged, unstaged, or ref-targeted changes.
- **Python CLI**: `python scripts/git_diff.py [--staged] [--path <file>] [--repo-path .]`
- **Groovy CLI**: `groovy scripts/git_diff.groovy [--staged] [--ref HEAD~1] [--path <file>] [--stat] [--repo-path .] [--json]`
- **Groovy Binding**: `[staged: true, ref: "HEAD~1", path: "src/Main.java", repo_path: "."]`
- **Output**: Structured Map/JSON containing `diff`, `stat`, `files_changed`, `staged`, `ref`.

### 3. `git_log` (`git_log.py`, `git_log.groovy`)
Retrieves formatted commit history with author, date, hash, and subject/body metadata.
- **Python CLI**: `python scripts/git_log.py [--limit 10] [--author <name>] [--repo-path .]`
- **Groovy CLI**: `groovy scripts/git_log.groovy [--limit 10] [--author <name>] [--since "2025-01-01"] [--grep "feat"] [--repo-path .]`
- **Groovy Binding**: `[limit: 10, author: "agent", repo_path: "."]`
- **Output**: Structured Map/JSON containing `total` and list of `commits` (`commit`, `abbrev_hash`, `author`, `email`, `date`, `subject`, `body`).

### 4. `git_commit` (`git_commit.py`, `git_commit.groovy`)
Creates a structured semantic commit with secret validation (`.env`, `.pem`, `.key`, `credentials.json`) and automated PII path sanitization.
- **Python CLI**: `python scripts/git_commit.py -m "feat: add user auth" [-a] [--repo-path .]`
- **Groovy CLI**: `groovy scripts/git_commit.groovy -m "feat: add user auth" [-a] [--repo-path .] [--json]`
- **Groovy Binding**: `[message: "feat: add user auth", all: true, repo_path: "."]`
- **Output**: Structured Map/JSON containing `status`, `commit_hash`, `message`, `output`.

## Resources
- **`resources/git_guidelines.md`**: Commit message standards, branching rules, secret scrubbing guidelines, and PII protection policies.
