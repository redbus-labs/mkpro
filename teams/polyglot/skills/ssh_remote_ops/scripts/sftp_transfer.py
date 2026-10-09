#!/usr/bin/env python3
import sys
import os
import argparse
import subprocess
import shutil
import json

SESSION_FILE = os.path.join(".mkpro", "ssh_sessions.json")

def main():
    parser = argparse.ArgumentParser(description="Transfer files between local and remote environments.")
    parser.add_argument("--action", choices=["upload", "download"], required=True, help="Transfer direction.")
    parser.add_argument("--local-path", required=True, help="Local file path.")
    parser.add_argument("--remote-path", required=True, help="Remote file path.")
    parser.add_argument("--alias", default="default", help="Session alias (default: default).")
    args = parser.parse_args()

    session = None
    if os.path.exists(SESSION_FILE):
        try:
            with open(SESSION_FILE, "r", encoding="utf-8") as f:
                sessions = json.load(f)
                session = sessions.get(args.alias)
        except Exception:
            session = None

    if session:
        host = session["host"]
        user = session["username"]
        port = session.get("port", 22)
        key_path = session.get("key_path")

        scp_cmd = ["scp", "-P", str(port), "-o", "StrictHostKeyChecking=no"]
        if key_path:
            scp_cmd.extend(["-i", key_path])

        if args.action == "upload":
            scp_cmd.extend([args.local_path, f"{user}@{host}:{args.remote_path}"])
        else:
            scp_cmd.extend([f"{user}@{host}:{args.remote_path}", args.local_path])

        res = subprocess.run(scp_cmd, capture_output=True, text=True)
        if res.returncode != 0:
            sys.stderr.write(f"SFTP transfer failed: {res.stderr}\n")
            sys.exit(res.returncode)
    else:
        # Local fallback copy
        if args.action == "upload":
            os.makedirs(os.path.dirname(os.path.abspath(args.remote_path)), exist_ok=True)
            shutil.copy2(args.local_path, args.remote_path)
        else:
            os.makedirs(os.path.dirname(os.path.abspath(args.local_path)), exist_ok=True)
            shutil.copy2(args.remote_path, args.local_path)

    out = {
        "status": "success",
        "action": args.action,
        "local_path": args.local_path,
        "remote_path": args.remote_path,
        "session": args.alias
    }
    print(json.dumps(out, indent=2))

if __name__ == "__main__":
    main()
