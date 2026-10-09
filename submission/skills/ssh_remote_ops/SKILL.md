# SSH Remote Operations Skill

## Description
Complete remote Ubuntu/Linux administration tools: SSH session management, remote bash execution, and SFTP file synchronization. Available in both native Python (`.py`) and native Groovy (`.groovy`) implementations.

## Usage
Used by UbuntuOps and SysAdmin agents to administer remote servers, run diagnostics, manage services, and synchronize configuration files.

## Scripts

### 1. `ssh_connect` (`ssh_connect.py`, `ssh_connect.groovy`)
Registers and validates SSH connection credentials and stores active session metadata in `.mkpro/ssh_sessions.json`.
- **Python CLI**: `python scripts/ssh_connect.py --host <ip> --username <user> [--password <pwd>] [--key-path <key>] [--alias default]`
- **Groovy CLI**: `groovy scripts/ssh_connect.groovy --host <ip> --username <user> [--password <pwd>] [--key-path <key>] [--port 22] [--alias default]`
- **Groovy Binding**: `[host: "192.168.1.100", username: "ubuntu", key_path: "~/.ssh/id_rsa", port: 22, alias: "web-server"]`
- **Output**: Structured Map/JSON containing `status`, `alias`, `host`, `port`, `user`, `auth_type`, `session_store`.

### 2. `ssh_exec` (`ssh_exec.py`, `ssh_exec.groovy`)
Executes remote shell commands across an active SSH connection session with fallback mode when unconfigured.
- **Python CLI**: `python scripts/ssh_exec.py --command "systemctl status nginx" [--alias default] [--timeout 30]`
- **Groovy CLI**: `groovy scripts/ssh_exec.groovy --command "systemctl status nginx" [--alias default] [--timeout 30]`
- **Groovy Binding**: `[command: "systemctl status nginx", alias: "default", timeout: 30]`
- **Output**: Structured Map/JSON containing `status` (`success`, `fallback`, `error`), `session`, `host`, `command`, `returncode`, `stdout`, `stderr`.

### 3. `sftp_transfer` (`sftp_transfer.py`, `sftp_transfer.groovy`)
Transfers files between local environment and remote host via SFTP / SCP.
- **Python CLI**: `python scripts/sftp_transfer.py --action upload|download --local-path <loc> --remote-path <rem> [--alias default]`
- **Groovy CLI**: `groovy scripts/sftp_transfer.groovy --action upload|download --local-path <loc> --remote-path <rem> [--alias default]`
- **Groovy Binding**: `[action: "upload", local_path: "/path/local.conf", remote_path: "/etc/nginx/nginx.conf", alias: "default"]`
- **Output**: Structured Map/JSON containing `status`, `action`, `local_path`, `remote_path`, `session`, `bytes_transferred`.

## Resources
- **`resources/ssh_best_practices.md`**: SSH security standards, key authentication setup, non-root execution guidelines, and SFTP transfer practices.
