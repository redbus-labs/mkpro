# File Operations Skill

## Description
Essential file system utilities for reading, creating, editing, and listing files and directories across the project workspace. Available in both native Python (`.py`) and native Groovy (`.groovy`) implementations with identical capabilities and structured output.

## Usage
Used by agents whenever inspecting source files, modifying code, verifying file hierarchies, or writing new files. Both CLI arguments and in-memory parameter binding (`params` Map) are supported.

## Scripts

### 1. `read_file` (`read_file.py`, `read_file.groovy`)
Reads file content with line offset, limit slicing, and size tracking.
- **Python CLI**: `python scripts/read_file.py <path> [--start-line N] [--end-line M] [--line-numbers] [--encoding utf-8]`
- **Groovy CLI**: `groovy scripts/read_file.groovy <path> [--start-line N] [--end-line M] [--line-numbers] [--encoding utf-8] [--json]`
- **Groovy Binding**: `[path: "path/to/file", start_line: 1, end_line: 50, line_numbers: true]`
- **Output**: Structured Map/JSON containing `path`, `total_lines`, `size_bytes`, `start_line`, `end_line`, `lines_read`, `content`, `lines`.

### 2. `write_file` (`write_file.py`, `write_file.groovy`)
Creates or overwrites a file with content using atomic temp-file replacement and automatic parent directory creation.
- **Python CLI**: `python scripts/write_file.py <path> --content "..." [--mode write|append]`
- **Groovy CLI**: `groovy scripts/write_file.groovy <path> --content "..." [--mode write|append]`
- **Groovy Binding**: `[path: "path/to/file", content: "...", mode: "write"]`
- **Output**: Structured Map/JSON containing `status`, `path`, `bytes_written`, `mode`, `atomic`.

### 3. `edit_file` (`edit_file.py`, `edit_file.groovy`)
Performs in-place find/replace or patch editing with unified diff generation and existence validation.
- **Python CLI**: `python scripts/edit_file.py <path> --target "old" --replacement "new" [--diff] [--count 1]`
- **Groovy CLI**: `groovy scripts/edit_file.groovy <path> --target "old" --replacement "new" [--diff] [--count 1] [--regex]`
- **Groovy Binding**: `[path: "path/to/file", target: "old", replacement: "new", diff: false, count: 1]`
- **Output**: Structured Map/JSON containing `status`, `path`, `replacements`, `diff`, `written`.

### 4. `list_directory` (`list_directory.py`, `list_directory.groovy`)
Lists directory trees with recursive depth control, glob pattern matching, and hidden file filtering.
- **Python CLI**: `python scripts/list_directory.py [path] [-r] [--depth N] [--pattern "*.java"] [--json]`
- **Groovy CLI**: `groovy scripts/list_directory.groovy [path] [-r] [--depth N] [--pattern "*.java"] [--include-hidden] [--json]`
- **Groovy Binding**: `[path: ".", recursive: true, depth: 3, pattern: "*.groovy", include_hidden: false]`
- **Output**: Structured Map/JSON containing `directory`, `total_entries`, and list of `entries` (`name`, `path`, `type`, `size_bytes`, `hidden`).

## Resources
- **`resources/file_conventions.md`**: Project file organization, naming conventions, and file access standards.
