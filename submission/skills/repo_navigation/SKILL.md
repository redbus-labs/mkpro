# Skill: Repo Navigation & Editing

**Skill Name**: `repo_navigation`  
**Description**: Repository navigation, ripgrep code searching, file viewing, targeted string replacement, and test execution for Google ADK agents.

---

## Capabilities & Tool Specifications

### 1. `ripgrep_search`
Perform fast pattern or regular expression search across the repository.
- **`query`**: (string, required) Pattern or regex to match.
- **`path`**: (string, default=".") Directory or file scope.
- **`file_pattern`**: (string, optional) Include glob filter (e.g. `*.py`).

### 2. `file_search`
Search for file paths matching a glob pattern.
- **`pattern`**: (string, required) Glob search expression.

### 3. `read_file_content`
Inspect file contents with line numbering.
- **`file_path`**: (string, required) Path to target file.
- **`start_line`**: (int, optional) Start line (1-indexed).
- **`end_line`**: (int, optional) End line.

### 4. `search_and_replace`
Perform exact or regex search-and-replace using `scripts/search_and_edit.py`.
- **`file_path`**: (string, required) Target file path.
- **`search`**: (string, required) Target pattern to find.
- **`replace`**: (string, required) Replacement string.
- **`is_regex`**: (bool, default=false) Whether search pattern is regex.

### 5. `run_tests`
Execute test runner in workspace sandbox.
- **`command`**: (string, default="pytest") Shell test invocation.
