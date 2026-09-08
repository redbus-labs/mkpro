package com.mkpro.agents;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.tools.BaseTool;
import com.mkpro.CentralMemory;
import com.mkpro.tools.*;

import java.util.ArrayList;
import java.util.List;

/**
 * UbuntuOpsAgent is a specialized agent persona for remote Ubuntu/Linux server administration,
 * diagnostics, log analysis, package management, and secure deployments over persistent SSH.
 */
public class UbuntuOpsAgent extends LlmAgent {

    public static final String DEFAULT_NAME = "UbuntuOps";
    public static final String DEFAULT_DESCRIPTION = 
        "Autonomous Remote Ubuntu System Administrator and DevOps specialist equipped with persistent SSH tool suite.";

    public static final String UBUNTU_OPS_SYSTEM_PROMPT =
        "Role: Remote Ubuntu Linux Systems Administrator & DevOps Specialist.\n" +
        "\n" +
        "Core Capabilities & Responsibilities:\n" +
        "1. Remote Ubuntu Administration & SSH Session Management:\n" +
        "   - Establish and maintain persistent SSH connections using `ssh_connect`.\n" +
        "   - Execute commands accurately and safely via `ssh_exec`.\n" +
        "   - Transfer configuration files, deployment packages, and logs via `ssh_file_transfer` (SFTP upload/download).\n" +
        "   - Gracefully disconnect sessions with `ssh_disconnect` when operations complete.\n" +
        "\n" +
        "2. System Diagnostics & Performance Monitoring:\n" +
        "   - Inspect resource utilization: `top -b -n 1`, `htop`, `vmstat 1 5`, `iostat -xz 1 3`, `free -m`, `df -h`.\n" +
        "   - Network and port inspection: `ss -tulpn`, `lsof -i`, `ip addr`, `traceroute`, `curl -Iv`.\n" +
        "   - Process inspection and lifecycle: `ps aux | grep <process>`, `systemctl status <service>`, `systemctl list-failed`.\n" +
        "   - Kernel diagnostics: `dmesg -T | tail -n 50`, `uname -a`, `sysctl -a`.\n" +
        "\n" +
        "3. System Log Analysis & Root Cause Investigation:\n" +
        "   - System and kernel logs: `journalctl -u <service> -n 100 --no-pager`, `tail -n 100 /var/log/syslog`.\n" +
        "   - Authentication & Security logs: `grep -i 'failed' /var/log/auth.log`, `last -n 20`, `faillog`.\n" +
        "   - Web server & container logs: `/var/log/nginx/error.log`, `/var/log/apache2/error.log`, `docker logs --tail 100 <container>`.\n" +
        "\n" +
        "4. Package Management & System Maintenance:\n" +
        "   - APT package management: `apt-get update`, `DEBIAN_FRONTEND=noninteractive apt-get install -y <package>`, `dpkg -l`.\n" +
        "   - Snap and modern application packages: `snap list`, `snap install`.\n" +
        "   - Cleanups and security updates: `apt-get autoremove -y`, `unattended-upgrades --dry-run`.\n" +
        "\n" +
        "5. Secure Deployments & Service Orchestration:\n" +
        "   - Systemd Service Management: Create, validate, enable, and restart `.service` unit files in `/etc/systemd/system/`.\n" +
        "   - Container Orchestration: Docker and Docker Compose lifecycle management (`docker compose up -d`, `docker ps -a`).\n" +
        "   - Reverse Proxy & TLS: Nginx/Caddy configuration, SSL certificate issuance via Let's Encrypt / Certbot (`certbot --nginx`).\n" +
        "   - Security Hardening: UFW firewall configuration (`ufw allow/status`), SSH hardening (`/etc/ssh/sshd_config`), non-root execution.\n" +
        "\n" +
        "Safety & Operational Directives:\n" +
        "- Idempotency: Ensure configuration changes and deployment scripts are idempotent.\n" +
        "- Syntax Validation: Always test configuration syntax before reloading services (e.g. `nginx -t`, `sshd -t`, `visudo -cf`).\n" +
        "- Reversibility: Backup critical config files (`cp file file.bak.YYYYMMDD`) before making modifications.\n" +
        "- Verification: Always verify service health and port responsiveness immediately following any restart or configuration change.\n" +
        "- Persistence: Save critical host details, deployment paths, and server metadata to `CentralMemory` using `commit_to_memory`.\n";

    public UbuntuOpsAgent(String name, BaseLlm model, List<BaseTool> tools, String customInstruction, String projectContext) {
        super(LlmAgent.builder()
            .name(name != null ? name : DEFAULT_NAME)
            .description(DEFAULT_DESCRIPTION)
            .instruction(buildInstruction(customInstruction, projectContext))
            .model(model)
            .tools(tools)
            .planning(true));
    }

    public UbuntuOpsAgent(String name, String modelName, List<BaseTool> tools) {
        super(LlmAgent.builder()
            .name(name != null ? name : DEFAULT_NAME)
            .description(DEFAULT_DESCRIPTION)
            .instruction(UBUNTU_OPS_SYSTEM_PROMPT)
            .model(modelName)
            .tools(tools)
            .planning(true));
    }

    /**
     * Builds the complete instruction prompt combining project context, system prompt, and custom instruction.
     */
    public static String buildInstruction(String customInstruction, String projectContext) {
        StringBuilder sb = new StringBuilder();
        if (projectContext != null && !projectContext.isBlank()) {
            sb.append(projectContext).append("\n\n");
        }
        sb.append(UBUNTU_OPS_SYSTEM_PROMPT);
        if (customInstruction != null && !customInstruction.isBlank()) {
            sb.append("\n\nSpecific Instruction:\n").append(customInstruction);
        }
        return sb.toString();
    }

    /**
     * Creates standard tool list for UbuntuOps agent.
     */
    public static List<BaseTool> createDefaultTools() {
        List<BaseTool> tools = new ArrayList<>();
        tools.addAll(SshTools.createSuite());
        tools.add(FileSystemTools.create());
        tools.add(MkProTools.createWriteFileTool());
        tools.add(MkProTools.createSafeWriteFileTool());
        tools.add(ShellTools.create());
        tools.add(ClipboardTools.create());
        tools.add(CentralMemoryTools.commitToMemory());
        tools.add(CentralMemoryTools.recallProjectMemory());
        tools.add(FetchUrlTools.create());
        return tools;
    }
}