#!/usr/bin/env python3
import sys
import os
import argparse
import subprocess
import time
import json

def main():
    parser = argparse.ArgumentParser(description="Execute a script with a specified interpreter.")
    parser.add_argument("script_path", help="Path to script file.")
    parser.add_argument("--args", default="", help="Arguments to pass to the script.")
    parser.add_argument("--interpreter", choices=["python", "bash", "sh", "node", "groovy"], default=None)
    parser.add_argument("--working-dir", default=".", help="Working directory.")
    parser.add_argument("--timeout", type=int, default=120, help="Timeout in seconds.")
    args = parser.parse_args()

    if not os.path.exists(args.script_path):
        sys.stderr.write(f"Error: Script '{args.script_path}' not found.\n")
        sys.exit(1)

    interpreter = args.interpreter
    if interpreter is None:
        ext = os.path.splitext(args.script_path)[1].lower()
        if ext == ".py":
            interpreter = "python"
        elif ext in [".sh", ".bash"]:
            interpreter = "bash" if os.name != "nt" else "sh"
        elif ext == ".js":
            interpreter = "node"
        elif ext == ".groovy":
            interpreter = "groovy"
        else:
            interpreter = "python"

    cmd = f"{interpreter} {args.script_path} {args.args}".strip()
    start_time = time.time()
    try:
        res = subprocess.run(
            cmd,
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
            "script": args.script_path,
            "interpreter": interpreter,
            "returncode": res.returncode,
            "stdout": res.stdout,
            "stderr": res.stderr,
            "elapsed_seconds": elapsed
        }
        print(json.dumps(out, indent=2))
        sys.exit(res.returncode)
    except Exception as e:
        sys.stderr.write(f"Error executing script: {e}\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
