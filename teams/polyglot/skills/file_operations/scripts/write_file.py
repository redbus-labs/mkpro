#!/usr/bin/env python3
import sys
import os
import argparse
import json

def main():
    parser = argparse.ArgumentParser(description="Write or append content to a file.")
    parser.add_argument("path", help="Target file path.")
    parser.add_argument("--content", help="Content string to write (reads stdin if omitted).")
    parser.add_argument("--mode", choices=["write", "append"], default="write", help="Write mode.")
    parser.add_argument("--encoding", default="utf-8", help="Text encoding (default: utf-8).")
    args = parser.parse_args()

    if args.content is not None:
        content = args.content
    else:
        content = sys.stdin.read()

    try:
        dirname = os.path.dirname(os.path.abspath(args.path))
        if dirname and not os.path.exists(dirname):
            os.makedirs(dirname, exist_ok=True)
        
        open_mode = "w" if args.mode == "write" else "a"
        with open(args.path, open_mode, encoding=args.encoding) as f:
            f.write(content)
        
        res = {
            "status": "success",
            "path": os.path.abspath(args.path),
            "bytes_written": len(content.encode(args.encoding)),
            "mode": args.mode
        }
        print(json.dumps(res, indent=2))
    except Exception as e:
        sys.stderr.write(f"Error writing to '{args.path}': {e}\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
