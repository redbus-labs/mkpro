#!/usr/bin/env python3
import sys
import os
import argparse
import fnmatch
import json

def main():
    parser = argparse.ArgumentParser(description="List files and directories with depth and filtering.")
    parser.add_argument("path", nargs="?", default=".", help="Directory to list (default: current dir).")
    parser.add_argument("-r", "--recursive", action="store_true", help="Recursively list subdirectories.")
    parser.add_argument("--depth", type=int, default=3, help="Max depth for recursive listing (default: 3).")
    parser.add_argument("--pattern", default="*", help="Filename pattern/glob filter (default: *).")
    parser.add_argument("--json", action="store_true", help="Output results as JSON.")
    args = parser.parse_args()

    target_dir = os.path.abspath(args.path)
    if not os.path.exists(target_dir):
        sys.stderr.write(f"Error: Path '{args.path}' not found.\n")
        sys.exit(1)
    if not os.path.isdir(target_dir):
        sys.stderr.write(f"Error: Path '{args.path}' is not a directory.\n")
        sys.exit(1)

    results = []
    base_depth = target_dir.rstrip(os.sep).count(os.sep)

    for root, dirs, files in os.walk(target_dir):
        current_depth = root.rstrip(os.sep).count(os.sep) - base_depth
        if not args.recursive and current_depth > 0:
            break
        if args.recursive and current_depth >= args.depth:
            dirs.clear()
            continue

        for d in sorted(dirs):
            rel_dir = os.path.relpath(os.path.join(root, d), target_dir)
            results.append({"name": d, "path": rel_dir, "type": "directory"})

        for f in sorted(files):
            if fnmatch.fnmatch(f, args.pattern):
                full_f = os.path.join(root, f)
                rel_f = os.path.relpath(full_f, target_dir)
                size = os.path.getsize(full_f) if os.path.exists(full_f) else 0
                results.append({"name": f, "path": rel_f, "type": "file", "size_bytes": size})

    if args.json:
        print(json.dumps(results, indent=2))
    else:
        for item in results:
            prefix = "[DIR] " if item["type"] == "directory" else f"[{item.get('size_bytes', 0):8d} B] "
            print(f"{prefix}{item['path']}")

if __name__ == "__main__":
    main()
