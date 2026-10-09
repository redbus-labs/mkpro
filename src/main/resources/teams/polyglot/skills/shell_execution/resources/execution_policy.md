# Execution Policy & Security Controls

1. **Timeout Restrictions**: Default timeout is set to 120s unless overridden. Long-running builds must be monitored.
2. **Blocked Dangerous Commands**: Fork bombs, raw destructive disk writes (`dd if=/dev/zero of=/dev/sdX`), `rm -rf /` are strictly prohibited.
3. **Working Directory Isolation**: Commands default to project root unless an explicit sub-directory is supplied.
4. **Environment Isolation**: Subprocesses inherit sanitized environment variables.
