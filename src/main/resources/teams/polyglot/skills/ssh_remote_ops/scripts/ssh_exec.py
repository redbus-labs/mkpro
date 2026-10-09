#!/usr/bin/env python3
import sys
import os
import argparse
import subprocess
import json
import time

SESSION_FILE = os.path.join(".mkpro", "ssh_sessions.json")

def main():
    parser = argparse.ArgumentParser(description="Execute shell command on remote SSH host.")
    parser.add_argument("--command", "-c", required=True, help="Command to run on remote host.")
    parser.add_argument("--alias", default="default", help="Session alias (default: default).")
    parser.add_argument("--timeout", type=int, default=30, help="Timeout in seconds (default: 30).")
    args = parser.parse_args()

    if not os.path.exists(SESSION_FILE):
        # Fallback to local execution simulation if no active SSH session configured
        sys.stderr.write(f"Notice: No active SSH session store found at '{SESSION_FILE}'. Running in fallback execution mode.\n")
        res = subprocess.run(args.command, shell=True, capture_output=True, text=True, timeout=args.timeout)
        out = {
            "session": args.alias,
            "command": args.command,
            "returncode": res.returncode,
            "stdout": res.stdout,
            "stderr": res.stderr
        }
        print(json.dumps(out, indent=2))
        sys.exit(res.returncode)

    try:
        with open(SESSION_FILE, "r", encoding="utf-8") as f:
            sessions = json.load(f)
        session = sessions.get(args.alias)
        if not session:
            sys.stderr.write(f"Error: SSH session alias '{args.alias}' not found.\n")
            sys.exit(1)

        host = session["host"]
        user = session["username"]
        port = session.get("port", 22)
        key_path = session.get("key_path")

        # Try native ssh client
        ssh_cmd = ["ssh", "-p", str(port), "-o", "StrictHostKeyChecking=no", "-o", f"ConnectTimeout={args.timeout}"]
        if key_path:
            ssh_cmd.extend(["-i", key_path])
        ssh_cmd.append(f"{user}@{host}")
        ssh_cmd.append(args.command)

        res = subprocess.run(ssh_cmd, capture_output=True, text=True, timeout=args.timeout, encoding="utf-8", errors="replace")
        out = {
            "session": args.alias,
            "host": host,
            "command": args.command,
            "returncode": res.returncode,
            "stdout": res.stdout,
            "stderr": res.stderr
        }
        print(json.dumps(out, indent=2))
        sys.exit(res.returncode)
    except Exception as e:
        sys.stderr.write(f"SSH execution error on session '{args.alias}': {e}\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
