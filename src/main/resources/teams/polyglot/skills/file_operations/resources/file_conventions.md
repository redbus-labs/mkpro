# File Conventions & Policies

1. **Encoding**: All text files must be encoded in standard UTF-8 without BOM.
2. **Line Endings**: Use standard LF (`\n`) or CRLF (`\r\n`) consistently based on target host OS.
3. **Atomic Writes**: Writes should create parent directories automatically if absent.
4. **Safety & Diffing**: Large replacements should be previewed using diff generation before committing changes.
5. **Path Separators**: Use forward slashes `/` or `os.path` normalization for cross-platform portability.
