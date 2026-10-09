#!/usr/bin/env python3
"""
search_and_edit.py

Helper script for searching and editing source files within a repository.
Supports literal and regex search-and-replace operations with optional dry-run and backup.
"""

import sys
import re
import argparse
from pathlib import Path


def search_and_replace(
    file_path: Path,
    search_pattern: str,
    replacement: str,
    is_regex: bool = False,
    dry_run: bool = False,
    backup: bool = False,
) -> bool:
    """Performs search and replace on the specified file."""
    if not file_path.exists():
        print(f"Error: File '{file_path}' does not exist.", file=sys.stderr)
        return False

    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"Error reading '{file_path}': {e}", file=sys.stderr)
        return False

    if is_regex:
        try:
            pattern = re.compile(search_pattern, re.MULTILINE)
        except re.error as e:
            print(f"Error: Invalid regex pattern '{search_pattern}': {e}", file=sys.stderr)
            return False

        if not pattern.search(content):
            print(f"Warning: Pattern '{search_pattern}' not found in '{file_path}'.")
            return False

        new_content, count = pattern.subn(replacement, content)
    else:
        if search_pattern not in content:
            print(f"Warning: Literal string '{search_pattern}' not found in '{file_path}'.")
            return False

        count = content.count(search_pattern)
        new_content = content.replace(search_pattern, replacement)

    print(f"Found {count} occurrence(s) in '{file_path}'.")

    if dry_run:
        print("[Dry Run] Changes not written to disk.")
        return True

    if backup:
        backup_path = file_path.with_suffix(file_path.suffix + ".bak")
        try:
            backup_path.write_text(content, encoding="utf-8")
            print(f"Created backup at '{backup_path}'.")
        except Exception as e:
            print(f"Warning: Failed to create backup '{backup_path}': {e}", file=sys.stderr)

    try:
        file_path.write_text(new_content, encoding="utf-8")
        print(f"Successfully updated '{file_path}'.")
        return True
    except Exception as e:
        print(f"Error writing updates to '{file_path}': {e}", file=sys.stderr)
        return False


def main():
    parser = argparse.ArgumentParser(
        description="Search and replace text in a file for repo navigation and patching."
    )
    parser.add_argument("-f", "--file_path", required=True, type=Path, help="Target file path")
    parser.add_argument("-s", "--search", required=True, type=str, help="Search string or regex")
    parser.add_argument("-r", "--replace", required=True, type=str, help="Replacement string")
    parser.add_argument("-re", "--regex", action="store_true", help="Treat search string as regex")
    parser.add_argument("--dry_run", action="store_true", help="Perform dry run without modifying file")
    parser.add_argument("--backup", action="store_true", help="Create .bak file before modifying")

    args = parser.parse_args()

    success = search_and_replace(
        file_path=args.file_path,
        search_pattern=args.search,
        replacement=args.replace,
        is_regex=args.regex,
        dry_run=args.dry_run,
        backup=args.backup,
    )

    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
