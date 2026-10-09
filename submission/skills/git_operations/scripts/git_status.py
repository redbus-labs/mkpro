#!/usr/bin/env python3
import sys
import os
import argparse
import subprocess
import json

def run_git(args, cwd):
    cmd = ["git"] + args
    res = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return res.returncode, res.stdout.strip(), res.stderr.strip()

def main():
    parser = argparse.ArgumentParser(description="Get git working tree status.")
    parser.add_argument("--repo-path", default=".", help="Path to git repository.")
    args = parser.parse_args()

    code, branch, _ = run_git(["rev-parse", "--abbrev-ref", "HEAD"], args.repo_path)
    if code != 0:
        sys.stderr.write("Error: Not a git repository.\n")
        sys.exit(1)

    code, porcelain, _ = run_git(["status", "--porcelain=v1"], args.repo_path)
    staged, unstaged, untracked = [], [], []

    for line in porcelain.splitlines():
        if len(line) < 3:
            continue
        s1, s2, file_path = line[0], line[1], line[3:].strip()
        if s1 in ["M", "A", "D", "R", "C"]:
            staged.append({"status": s1, "path": file_path})
        if s2 in ["M", "D"]:
            unstaged.append({"status": s2, "path": file_path})
        if s1 == "?" and s2 == "?":
            untracked.append(file_path)

    out = {
        "branch": branch,
        "clean": len(staged) == 0 and len(unstaged) == 0 and len(untracked) == 0,
        "staged": staged,
        "unstaged": unstaged,
        "untracked": untracked
    }
    print(json.dumps(out, indent=2))

if __name__ == "__main__":
    main()
