#!/usr/bin/env python3
import sys
import os
import argparse
import subprocess
import re
import json

FORBIDDEN_PATTERNS = [
    r"\.env($|\.local)",
    r".*\.pem$",
    r".*\.key$",
    r"id_rsa",
    r"credentials\.json"
]

def sanitize_message(msg):
    # Strip user paths like C:\Users\xxx or /home/xxx
    msg = re.sub(r"[A-Za-z]:\\[Uu]sers\\[^\\]+\\", "./", msg)
    msg = re.sub(r"/home/[^/]+/", "~/", msg)
    return msg

def main():
    parser = argparse.ArgumentParser(description="Create a semantic git commit with secret checks.")
    parser.add_argument("-m", "--message", required=True, help="Commit message.")
    parser.add_argument("-a", "--all", action="store_true", help="Automatically stage modified and deleted files.")
    parser.add_argument("--repo-path", default=".", help="Path to repository.")
    args = parser.parse_args()

    # 1. Check staged files for forbidden patterns
    res = subprocess.run(["git", "diff", "--cached", "--name-only"], cwd=args.repo_path, capture_output=True, text=True)
    staged_files = res.stdout.strip().splitlines()

    for f in staged_files:
        for pat in FORBIDDEN_PATTERNS:
            if re.match(pat, os.path.basename(f), re.IGNORECASE):
                sys.stderr.write(f"Security Alert: Attempting to commit forbidden secret file '{f}'. Commit aborted.\n")
                sys.exit(1)

    clean_msg = sanitize_message(args.message)
    cmd = ["git", "commit"]
    if args.all:
        cmd.append("-a")
    cmd.extend(["-m", clean_msg])

    res = subprocess.run(cmd, cwd=args.repo_path, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if res.returncode != 0:
        sys.stderr.write(f"Git commit failed: {res.stderr}\n")
        sys.exit(res.returncode)

    print(json.dumps({"status": "success", "output": res.stdout.strip()}, indent=2))

if __name__ == "__main__":
    main()
