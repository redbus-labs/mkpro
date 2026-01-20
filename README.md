# mkpro - The AI Software Engineering Team

`mkpro` is an advanced, modular CLI assistant built on the Google Agent Development Kit (ADK). It orchestrates a team of **8 specialized AI agents** to autonomously handle complex software engineering tasks, from coding and testing to security audits and cloud deployment. It supports a multi-provider backend, allowing you to mix and match local models (Ollama) with powerful cloud models (Gemini, Bedrock).

## 🤖 Meet the Team

Your `mkpro` instance is not just a chatbot; it's a team of experts led by a Coordinator.

| Agent | Role & Capabilities |
| :--- | :--- |
| **Coordinator** | **Team Lead**. Orchestrates the workflow, manages long-term memory, and delegates tasks to the right specialist. It is your primary interface. |
| **Coder** | **Software Engineer**. Reads, writes, and refactors code. Analyzes project structure and implements features. |
| **SysAdmin** | **System Operator**. Executes shell commands, manages files, and runs build tools (Maven, Gradle, npm). |
| **Tester** | **QA Engineer**. Writes unit and integration tests, runs test suites, and analyzes failure reports to suggest fixes. |
| **DocWriter** | **Technical Writer**. Maintains `README.md`, generates Javadocs/Docstrings, and ensures documentation stays in sync with code. |
| **SecurityAuditor** | **Security Analyst**. Scans code for vulnerabilities (SQLi, XSS, secrets), runs audit tools (`npm audit`), and recommends hardening steps. |
| **Architect** | **Principal Engineer**. Reviews high-level design, analyzes cohesion/coupling, enforces design patterns, and plans refactoring. |
| **DatabaseAdmin** | **DBA**. Writes complex SQL queries, creates schema migration scripts, and analyzes database structures. |
| **DevOps** | **SRE / Cloud Engineer**. Writes Dockerfiles, Kubernetes manifests, CI/CD configs, and interacts with cloud CLIs (AWS, GCP). |

## 🚀 Key Features

- **Granular Configuration**: Assign different models to different agents. Use a cheap, fast model (e.g., `gemini-1.5-flash`) for the *Coder* and a reasoning-heavy model (e.g., `claude-3-5-sonnet`) for the *Architect*.
- **Persistent Memory**:
    - **Central Store**: Project summaries and agent configurations are saved to `~/.mkpro/central_memory.db`.
    - **Local Session**: Context is managed efficiently with `/compact` to save tokens.
- **Multi-Provider**: seamless switching between **Ollama** (Local), **Gemini** (Google), and **Bedrock** (AWS).
- **Interactive CLI**: Easy-to-use menus for configuration (`/config`) and status monitoring (`/status`).

## 🛠️ Setup & Installation

### Prerequisites
- **Java 17+** and **Maven** (for building).
- **Ollama** (Optional): For local privacy-first inference.
- **Google API Key** (Optional): Set `GOOGLE_API_KEY` for Gemini.
- **AWS Credentials** (Optional): Set standard AWS env vars for Bedrock.

### Build
```bash
mvn clean package
```
This generates the native Windows executable `target/mkpro.exe` and a fat JAR.

### Run
```bash
./target/mkpro.exe
```

## 🎮 Usage Guide

### Configuration
Use the interactive menu to set up your team:
```text
> /config
Select Agent to configure:
  [1] Architect (Current: OLLAMA - devstral-small-2)
  [2] Coder (Current: GEMINI - gemini-1.5-pro)
  ...
```

### Real-World Use Cases

#### 1. Full Feature Implementation
> **User**: "Implement a new user login endpoint with JWT authentication."
>
> **Coordinator**:
> 1.  Asks **Architect** to design the API interface and security capability.
> 2.  Asks **Coder** to write the Controller, Service, and JWT utility classes.
> 3.  Asks **Tester** to generate JUnit tests for the new endpoint.
> 4.  Asks **DocWriter** to update the API documentation.

#### 2. Security Hardening
> **User**: "Audit the project for security vulnerabilities."
>
> **Coordinator**:
> 1.  Delegates to **SecurityAuditor** to scan `pom.xml`/`package.json` and source code.
> 2.  **SecurityAuditor** identifies a hardcoded secret or vulnerable lib.
> 3.  **Coordinator** asks **Coder** to apply the fix.
> 4.  **Coordinator** asks **Tester** to verify nothing broke.

#### 3. Cloud Migration
> **User**: "Containerize this application for deployment to AWS."
>
> **Coordinator**:
> 1.  Delegates to **DevOps** to write a `Dockerfile` and `docker-compose.yml`.
> 2.  **DevOps** creates AWS ECS task definitions.
> 3.  **SysAdmin** builds the docker image locally to verify it builds.

## ⌨️ Command Reference

| Command | Description |
| :--- | :--- |
| `/help` | Show this list. |
| `/status` | **Dashboard**. View agent models, providers, and memory stats. |
| `/config` | **Configure Team**. Interactive menu to set agent models/providers. Settings are saved. |
| `/init` | **Learn Project**. Agents scan and memorize the project structure. |
| `/re-init` | **Refresh Memory**. Re-scan the project if structure changed significantly. |
| `/provider` | Quick switch for the **Coordinator's** provider. |
| `/model` | Quick switch for the **Coordinator's** model. |
| `/compact` | **Save Tokens**. Summarize history and start fresh. |
| `/reset` | Clear session memory. |
| `exit` | Quit. |

## 🏗️ Architecture

The project is modularized for maintainability:
- `com.mkpro.MkPro`: Main entry point and CLI loop.
- `com.mkpro.agents.AgentManager`: Logic for creating and delegating to sub-agents.
- `com.mkpro.tools.MkProTools`: Factory for all tool implementations (File I/O, Shell, Web).
- `com.mkpro.models`: Data classes for configuration and persistence.