# Git Semantic Commit Guidelines & PII Rules

## Commit Message Format
```
[type]: [short summary of changes] [mkpro]

[Detailed description of what was changed and why]

---
AI Resource Usage (Session [sessionId]):
[Token breakdown]
```

## Types
- `feat`: New feature for the user.
- `fix`: Bug fix.
- `refactor`: Code change that neither fixes a bug nor adds a feature.
- `docs`: Documentation updates.
- `test`: Adding or correcting tests.
- `chore`: Build process, auxiliary tools, package configurations.

## PII & Secret Protection Rules
1. Never commit `.env`, `*.pem`, `*.key`, `id_rsa`, `credentials.json`.
2. Strip local user paths (e.g. `C:\Users\...`, `/home/...`).
3. Unstage sensitive secrets before creating commit.
