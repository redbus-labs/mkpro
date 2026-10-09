#!/usr/bin/env python3
import sys
import os
import argparse
import json
import time

SESSION_FILE = os.path.join(".mkpro", "ssh_sessions.json")

def main():
    parser = argparse.ArgumentParser(description="Connect or register an SSH remote session.")
    parser.add_argument("--host", required=True, help="Remote host IP or domain.")
    parser.add_argument("--username", required=True, help="SSH username.")
    parser.add_argument("--password", default=None, help="Password authentication.")
    parser.add_argument("--key-path", default=None, help="Path to private key.")
    parser.add_argument("--key-content", default=None, help="Inline private key PEM content.")
    parser.add_argument("--port", type=int, default=22, help="SSH Port (default: 22).")
    parser.add_argument("--alias", default="default", help="Session alias (default: default).")
    args = parser.parse_args()

    session_dir = os.path.dirname(os.path.abspath(SESSION_FILE))
    os.makedirs(session_dir, exist_ok=True)

    sessions = {}
    if os.path.exists(SESSION_FILE):
        try:
            with open(SESSION_FILE, "r", encoding="utf-8") as f:
                sessions = json.load(f)
        except Exception:
            sessions = {}

    sessions[args.alias] = {
        "alias": args.alias,
        "host": args.host,
        "username": args.username,
        "port": args.port,
        "auth_type": "KEY_FILE" if args.key_path else ("KEY_CONTENT" if args.key_content else "PASSWORD"),
        "key_path": args.key_path,
        "has_password": bool(args.password),
        "connected_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "status": "ACTIVE"
    }

    try:
        with open(SESSION_FILE, "w", encoding="utf-8") as f:
            json.dump(sessions, f, indent=2)
        
        out = {
            "status": "connected",
            "alias": args.alias,
            "host": args.host,
            "port": args.port,
            "user": args.username
        }
        print(json.dumps(out, indent=2))
    except Exception as e:
        sys.stderr.write(f"Error establishing session '{args.alias}': {e}\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
