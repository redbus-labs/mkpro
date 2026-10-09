#!/usr/bin/env python3
import sys
import os
import argparse
import subprocess

def main():
    parser = argparse.ArgumentParser(description="Show git diff.")
    parser.add_argument("--repo-path", default=".", help="Path to repository.")
    parser.add_argument("--staged", action="store_true", help="View staged changes.")
    parser.add_argument("--path", default=None, help="Optional specific file path.")
    args = parser.parse_args()

    cmd = ["git", "diff"]
    if args.staged:
        cmd.append("--cached")
    if args.path:
        cmd.extend(["--", args.path])

    res = subprocess.run(cmd, cwd=args.repo_path, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if res.returncode != 0:
        sys.stderr.write(f"Error executing git diff: {res.stderr}\n")
        sys.exit(res.returncode)
    
    print(res.stdout if res.stdout else "No changes.")

if __name__ == "__main__":
    main()
