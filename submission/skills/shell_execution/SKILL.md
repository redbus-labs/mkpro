# Shell Execution Skill

## Description
Secure and managed command-line and script execution capabilities across supported operating systems (Windows, Linux, macOS) with timeout enforcement and output stream capturing. Available in both native Python (`.py`) and native Groovy (`.groovy`) implementations.

## Usage
Used by agents to run build commands, execute test suites, run diagnostics, and invoke multi-line or standalone scripts.

## Scripts

### 1. `run_command` (`run_command.py`, `run_command.groovy`)
Executes system shell commands with timeout, exit code, stdout, and stderr capturing.
- **Python CLI**: `python scripts/run_command.py --command "mvn test" [--timeout 120] [--working-dir .]`
- **Groovy CLI**: `groovy scripts/run_command.groovy --command "mvn test" [--timeout 120] [--working-dir .] [--json]`
- **Groovy Binding**: `[command: "mvn clean compile", timeout: 120, working_dir: "."]`
- **Output**: Structured Map/JSON containing `status` (`success`, `timeout`, `error`), `command`, `returncode`, `stdout`, `stderr`, `elapsed_seconds`.

### 2. `run_script` (`run_script.py`, `run_script.groovy`)
Executes multi-line bash/cmd/groovy/python/node scripts or standalone script files with auto-detected or specified interpreters.
- **Python CLI**: `python scripts/run_script.py <script_path> [--args "..."] [--interpreter python]`
- **Groovy CLI**: `groovy scripts/run_script.groovy <script_path> [--args "..."] [--interpreter groovy] [--inline "println 1+1"]`
- **Groovy Binding**: `[script_path: "script.groovy", args: "--foo bar", interpreter: "groovy", inline: null]`
- **Output**: Structured Map/JSON containing `status`, `script`, `interpreter`, `returncode`, `stdout`, `stderr`, `elapsed_seconds`.

## Resources
- **`resources/execution_policy.md`**: Shell security constraints, allowlisted commands, timeout policies, and environment isolation rules.
