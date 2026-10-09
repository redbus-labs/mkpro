#!/usr/bin/env python3
import sys
import os
import argparse

def main():
    parser = argparse.ArgumentParser(description="Read file content with optional line slicing.")
    parser.add_argument("path", help="Path to the file to read.")
    parser.add_argument("--start-line", type=int, default=1, help="1-indexed line to start reading from.")
    parser.add_argument("--end-line", type=int, default=None, help="1-indexed line to stop reading at.")
    parser.add_argument("--line-numbers", action="store_true", help="Include line numbers in output.")
    parser.add_argument("--encoding", default="utf-8", help="Text encoding (default: utf-8).")
    args = parser.parse_args()

    if not os.path.exists(args.path):
        sys.stderr.write(f"Error: File '{args.path}' not found.\n")
        sys.exit(1)
    if os.path.isdir(args.path):
        sys.stderr.write(f"Error: Path '{args.path}' is a directory, not a file.\n")
        sys.exit(1)

    try:
        with open(args.path, "r", encoding=args.encoding, errors="replace") as f:
            lines = f.readlines()
        
        start = max(1, args.start_line) - 1
        end = args.end_line if args.end_line is not None else len(lines)
        sliced = lines[start:end]

        for idx, line in enumerate(sliced, start=start + 1):
            if args.line_numbers:
                sys.stdout.write(f"{idx:6d} | {line}")
            else:
                sys.stdout.write(line)
        if sliced and not sliced[-1].endswith("\n"):
            sys.stdout.write("\n")
    except Exception as e:
        sys.stderr.write(f"Error reading '{args.path}': {e}\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
