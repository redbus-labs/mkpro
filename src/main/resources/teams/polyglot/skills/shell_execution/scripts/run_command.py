#!/usr/bin/env python3
import sys
import os
import argparse
import subprocess
import time
import json

def main():
    parser = argparse.ArgumentParser(description="Execute a shell command with timeout and output capture.")
    parser.add_argument("--command", "-c", required=True, help="Shell command to execute.")
    parser.add_argument("--working-dir", "-d", default=".", help="Working directory for execution.")
    parser.add_argument("--timeout", "-t", type=int, default=120, help="Timeout in seconds (default: 120).")
    args = parser.parse_args()

    start_time = time.time()
    try:
        res = subprocess.run(
            args.command,
            cwd=args.working_dir,
            shell=True,
            capture_output=True,
            text=True,
            timeout=args.timeout,
            encoding="utf-8",
            errors="replace"
        )
        elapsed = round(time.time() - start_time, 3)
        out = {
            "status": "success",
            "command": args.command,
            "returncode": res.returncode,
            "stdout": res.stdout,
            "stderr": res.stderr,
            "elapsed_seconds": elapsed
        }
        print(json.dumps(out, indent=2))
        sys.exit(res.returncode)
    except subprocess.TimeoutExpired as e:
        elapsed = round(time.time() - start_time, 3)
        out = {
            "status": "timeout",
            "command": args.command,
            "returncode": -1,
            "stdout": e.stdout or "",
            "stderr": f"Command timed out after {args.timeout} seconds",
            "elapsed_seconds": elapsed
        }
        print(json.dumps(out, indent=2))
        sys.exit(124)
    except Exception as e:
        out = {
            "status": "error",
            "command": args.command,
            "returncode": -1,
            "stdout": "",
            "stderr": str(e),
            "elapsed_seconds": round(time.time() - start_time, 3)
        }
        print(json.dumps(out, indent=2))
        sys.exit(1)

if __name__ == "__main__":
    main()
