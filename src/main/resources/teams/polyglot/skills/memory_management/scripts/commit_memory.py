#!/usr/bin/env python3
import sys
import os
import argparse
import json
import time

DEFAULT_STORE = os.path.join(".mkpro", "project_memory.json")

def main():
    parser = argparse.ArgumentParser(description="Commit durable insight to project memory.")
    parser.add_argument("key", help="Key or identifier for the memory (e.g. 'architecture', 'conventions').")
    parser.add_argument("content", help="Information or insight to record.")
    parser.add_argument("--tag", default="general", help="Category tag.")
    parser.add_argument("--store", default=DEFAULT_STORE, help="Path to memory store file.")
    args = parser.parse_args()

    store_path = os.path.abspath(args.store)
    os.makedirs(os.path.dirname(store_path), exist_ok=True)

    data = {"entries": {}}
    if os.path.exists(store_path):
        try:
            with open(store_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {"entries": {}}

    if "entries" not in data:
        data["entries"] = {}

    data["entries"][args.key] = {
        "key": args.key,
        "content": args.content,
        "tag": args.tag,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    }

    try:
        with open(store_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        print(json.dumps({"status": "success", "key": args.key, "entries_count": len(data["entries"])}, indent=2))
    except Exception as e:
        sys.stderr.write(f"Error persisting memory: {e}\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
