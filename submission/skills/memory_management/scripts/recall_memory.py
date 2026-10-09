#!/usr/bin/env python3
import sys
import os
import argparse
import json

DEFAULT_STORE = os.path.join(".mkpro", "project_memory.json")

def main():
    parser = argparse.ArgumentParser(description="Recall memories and decisions.")
    parser.add_argument("--key", default=None, help="Exact key to retrieve.")
    parser.add_argument("--filter", default=None, help="Keyword filter substring.")
    parser.add_argument("--all", action="store_true", help="Retrieve all entries.")
    parser.add_argument("--store", default=DEFAULT_STORE, help="Path to memory store file.")
    args = parser.parse_args()

    store_path = os.path.abspath(args.store)
    if not os.path.exists(store_path):
        print(json.dumps({"entries": {}, "message": "No memory store found."}, indent=2))
        return

    try:
        with open(store_path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        sys.stderr.write(f"Error reading memory: {e}\n")
        sys.exit(1)

    entries = data.get("entries", {})
    if args.key:
        result = {args.key: entries.get(args.key)} if args.key in entries else {}
    elif args.filter:
        q = args.filter.lower()
        result = {k: v for k, v in entries.items() if q in k.lower() or q in v.get("content", "").lower() or q in v.get("tag", "").lower()}
    else:
        result = entries

    print(json.dumps({"results_count": len(result), "entries": result}, indent=2))

if __name__ == "__main__":
    main()
