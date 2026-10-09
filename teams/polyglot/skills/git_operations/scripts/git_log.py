#!/usr/bin/env python3
import sys
import os
import argparse
import subprocess
import json

def main():
    parser = argparse.ArgumentParser(description="Show formatted git log.")
    parser.add_argument("--repo-path", default=".", help="Path to repository.")
    parser.add_argument("--limit", "-n", type=int, default=10, help="Number of commits (default: 10).")
    parser.add_argument("--author", default=None, help="Filter by author.")
    args = parser.parse_args()

    format_str = "%H%x1f%an%x1f%ae%x1f%ad%x1f%s"
    cmd = ["git", "log", f"-n{args.limit}", f"--format={format_str}", "--date=iso"]
    if args.author:
        cmd.append(f"--author={args.author}")

    res = subprocess.run(cmd, cwd=args.repo_path, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if res.returncode != 0:
        sys.stderr.write(f"Error: {res.stderr}\n")
        sys.exit(res.returncode)

    commits = []
    for line in res.stdout.strip().splitlines():
        if not line:
            continue
        parts = line.split("\x1f")
        if len(parts) == 5:
            commits.append({
                "commit": parts[0],
                "author": parts[1],
                "email": parts[2],
                "date": parts[3],
                "subject": parts[4]
            })

    print(json.dumps(commits, indent=2))

if __name__ == "__main__":
    main()
