#!/usr/bin/env python3
import sys
import os
import argparse
import fnmatch
import json

IGNORED_DIRS = {".git", "node_modules", "target", "build", ".gradle", ".idea", "venv", "__pycache__"}

def main():
    parser = argparse.ArgumentParser(description="Find files and directories by name pattern.")
    parser.add_argument("--pattern", "-p", required=True, help="Glob pattern to match (e.g. '*.java', '*Config*').")
    parser.add_argument("--path", default=".", help="Root path to search.")
    parser.add_argument("--type", choices=["all", "file", "dir"], default="file", help="Entry type to match.")
    parser.add_argument("--max-results", type=int, default=100, help="Max results.")
    args = parser.parse_args()

    results = []
    root_dir = os.path.abspath(args.path)

    for root, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if d not in IGNORED_DIRS]
        
        if args.type in ["all", "dir"]:
            for d in dirs:
                if fnmatch.fnmatch(d, args.pattern):
                    results.append({"path": os.path.relpath(os.path.join(root, d), root_dir), "type": "directory"})
                    if len(results) >= args.max_results:
                        break

        if args.type in ["all", "file"]:
            for f in files:
                if fnmatch.fnmatch(f, args.pattern):
                    results.append({"path": os.path.relpath(os.path.join(root, f), root_dir), "type": "file"})
                    if len(results) >= args.max_results:
                        break
        
        if len(results) >= args.max_results:
            break

    print(json.dumps(results, indent=2))

if __name__ == "__main__":
    main()
