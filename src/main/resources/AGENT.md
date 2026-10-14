# AGENT.md: MkPro Autonomous Multi-Agent System

## 1. Overview & Architecture
MkPro is a Java-based, autonomous multi-agent developer system. It is designed to orchestrate complex software engineering workflows by leveraging large language models (LLMs) through the **Google GenAI ADK**.

**Key Architectural Pillars:**
- **Core Engine:** Java-based orchestration layer managing agent lifecycles, memory, and task execution.
- **ADK Integration:** Built on the Google GenAI ADK (Sarvam AI) for robust LLM interaction.
- **Persistence:** Uses **MapDB** for high-performance embedded key-value storage of project memory and states.
- **Connectivity:** Features persistent SSH capabilities (via `com.github.mwiede:jsch`) and smart event filtering for real-time interaction.
- **Web Interface:** Includes a built-in WebUI (Java-WebSocket + j2html) for monitoring and control, facilitating human-in-the-loop oversight.

## 2. Prerequisites & Build Commands
- **Java Version:** JDK 21 (defined in `pom.xml`).
- **Build Tool:** Apache Maven.

**Standard Commands:**
- **Build/Compile:** `mvn clean compile`
- **Run Tests:** `mvn test`
- **Package (Fat JAR):** `mvn clean package`
- **Execution:** Uses `maven-shade-plugin` to generate an executable JAR with `com.mkpro.MkPro` as the main class. Use provided scripts (`mkpro.sh` / `mkpro.bat`) for standardized entry points.

## 3. Project Structure & Key Packages
- `com.mkpro`: Main application entry and core logic.
- `com.mkpro.agents`: Definition and implementation of autonomous agent roles.
- `com.mkpro.tools`: Tooling and utility interfaces exposed to agents (e.g., file system access, scripting).
- `com.mkpro.memory`: Memory management systems, including MapDB storage.
- `com.mkpro.plugins`: Extensible plugin framework for added functionality.
- `com.mkpro.web`: WebUI implementation and WebSocket handlers.
- `src/main/resources`: Configuration and web assets.
- `teams/`: Agent configuration and orchestration definitions (included in JAR via `pom.xml` resource configuration).

## 4. Agent Team & Roles
The system operates via a collaborative team of specialized agents:
- **Coordinator:** Manages overall task decomposition and inter-agent communication.
- **Coder:** Responsible for implementation, code analysis, and refactoring.
- **CodeEditor:** Dedicated to applying surgical code changes.
- **Tester:** Manages test suite execution and validation.
- **Architect:** Designs system components and ensures consistency.
- **DocWriter:** Handles technical documentation updates.
- **GitAgent:** Manages VCS operations.
- **SysAdmin/UbuntuOps:** Manages environment, server-side tasks, and deployments.

## 5. Key Conventions & Rules
- **Git Operations:** **NEVER** commit or push changes unless explicitly authorized by the user. All VCS actions must remain strictly within the user's consent boundaries.
- **Performance Strategy:** Implements **Prefix Caching** and **Session-Start Compaction** to maintain context window efficiency and minimize latency.
- **Security:**
  - **PathValidator:** All file system operations must pass through validation.
  - **CommandPolicy:** Strict enforcement via `RemoteCommandPolicy` and `RemotePathValidator` for all remote/system-level interactions.
- **Integration:** WebUI and MapDB act as the unified source of truth for task status, persistent memory, and agent communication logs.
