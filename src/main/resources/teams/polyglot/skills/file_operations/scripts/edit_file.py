#!/usr/bin/env python3
import sys
import os
import argparse
import difflib
import json

def main():
    parser = argparse.ArgumentParser(description="Safely edit a file via search/replace with diff preview.")
    parser.add_argument("path", help="Path to file to edit.")
    parser.add_argument("--target", required=True, help="Target string/block to replace.")
    parser.add_argument("--replacement", required=True, help="Replacement string/block.")
    parser.add_argument("--diff", action="store_true", help="Print diff only and do not write.")
    parser.add_argument("--count", type=int, default=1, help="Max number of occurrences to replace (default: 1, 0=all).")
    args = parser.parse_args()

    if not os.path.exists(args.path):
        sys.stderr.write(f"Error: File '{args.path}' not found.\n")
        sys.exit(1)

    try:
        with open(args.path, "r", encoding="utf-8") as f:
            original = f.read()

        if args.target not in original:
            sys.stderr.write(f"Error: Target text not found in '{args.path}'.\n")
            sys.exit(1)

        count = -1 if args.count == 0 else args.count
        modified = original.replace(args.target, args.replacement, count)

        diff = list(difflib.unified_diff(
            original.splitlines(keepends=True),
            modified.splitlines(keepends=True),
            fromfile=f"a/{args.path}",
            tofile=f"b/{args.path}"
        ))

        diff_text = "".join(diff)

        if args.diff:
            print(diff_text if diff_text else "No changes detected.")
            return

        with open(args.path, "w", encoding="utf-8") as f:
            f.write(modified)

        res = {
            "status": "success",
            "path": os.path.abspath(args.path),
            "diff": diff_text
        }
        print(json.dumps(res, indent=2))
    except Exception as e:
        sys.stderr.write(f"Error editing '{args.path}': {e}\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
