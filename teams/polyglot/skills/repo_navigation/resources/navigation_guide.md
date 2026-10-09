# Repository Navigation & Codebase Exploration Guide

This resource provides domain knowledge and strategies for effective repository navigation across large Python codebases.

## 1. Fast Pattern Localization
- Use `ripgrep` for regex and symbol searches across files.
- Prefer searching definition signatures: `def <function_name>`, `class <class_name>`.
- Isolate relevant tests by filtering paths matching `test_*.py` or `tests/`.

## 2. AST and Scope Awareness
- Always inspect imports at top of file to determine dependencies.
- Trace class inheritance hierarchies when debugging method resolution order (MRO).
- Keep modifications within the minimal enclosing scope.

## 3. Safe Patching Principles
- Avoid changing whitespace outside targeted hunks.
- Retain comments and docstrings.
- Ensure all public APIs retain backward compatibility.
