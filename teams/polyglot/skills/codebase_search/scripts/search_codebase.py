#!/usr/bin/env python3
import sys
import os
import argparse
import re
import json

IGNORED_DIRS = {".git", "node_modules", "target", "build", ".gradle", ".idea", "venv", "__pycache__", ".mkpro"}

def main():
    parser = argparse.ArgumentParser(description="Search codebase for keywords or regular expressions.")
    parser.add_argument("query", help="Text or regex to search for.")
    parser.add_argument("--path", default=".", help="Root directory to search (default: current dir).")
    parser.add_argument("--file-types", default=None, help="Comma-separated file extensions (e.g. .java,.py,.yaml).")
    parser.add_argument("--regex", action="store_true", help="Treat query as regular expression.")
    parser.add_argument("--case-sensitive", action="store_true", help="Enable case sensitive matching.")
    parser.add_argument("--max-results", type=int, default=50, help="Maximum number of matches to return.")
    parser.add_argument("--json", action="store_true", help="Output matches as JSON.")
    args = parser.parse_args()

    flags = 0 if args.case_sensitive else re.IGNORECASE
    try:
        pattern = re.compile(args.query if args.regex else re.escape(args.query), flags)
    except Exception as e:
        sys.stderr.write(f"Invalid regex pattern: {e}\n")
        sys.exit(1)

    extensions = [ext.strip().lower() for ext in args.file_types.split(",")] if args.file_types else None
    matches = []
    total = 0

    root_dir = os.path.abspath(args.path)

    for root, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if d not in IGNORED_DIRS]
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if extensions and ext not in extensions:
                continue
            
            full_path = os.path.join(root, f)
            rel_path = os.path.relpath(full_path, root_dir)

            try:
                with open(full_path, "r", encoding="utf-8", errors="replace") as fp:
                    for line_no, line in enumerate(fp, start=1):
                        if pattern.search(line):
                            matches.append({
                                "file": rel_path,
                                "line": line_no,
                                "content": line.rstrip("\r\n")
                            })
                            total += 1
                            if total >= args.max_results:
                                break
            except Exception:
                pass
            if total >= args.max_results:
                break
        if total >= args.max_results:
            break

    if args.json:
        print(json.dumps({"total": len(matches), "matches": matches}, indent=2))
    else:
        for m in matches:
            print(f"{m['file']}:{m['line']}: {m['content']}")

if __name__ == "__main__":
    main()
