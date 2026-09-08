package com.mkpro.commands.impl;

import com.mkpro.CentralMemory;
import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.infra.ssh.AuthType;
import com.mkpro.infra.ssh.SshSandboxConfig;
import com.mkpro.infra.ssh.SshSessionManager;

import java.util.List;

import static com.mkpro.ui.AnsiColors.*;

/**
 * /ssh command handler:
 * - /ssh <command>                                  -> Directly executes bash/shell command on the active remote SSH session
 * - /ssh status                                     -> Lists all active SSH sessions and status
 * - /ssh config                                     -> Displays saved persistent SSH sandbox configuration
 * - /ssh autoconnect [on|off]                       -> Toggles auto-connection on application startup
 * - /ssh connect <host> <user> [pwd] [port] [alias] -> Connects to remote SSH server and saves configuration
 * - /ssh disconnect [alias]                         -> Terminates session
 * - /ssh transfer <upload|download> <localPath> <remotePath> [alias] -> SFTP transfer
 */
public class SshCommand implements Command {

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        var terminal = context.getTerminal();
        var writer = terminal.writer();
        SshSessionManager sshManager = SshSessionManager.getInstance();
        CentralMemory cm = context != null ? context.getCentralMemory() : CentralMemory.getInstance();

        if (args == null || args.length == 0) {
            printUsage(writer, sshManager);
            return;
        }

        String sub = args[0].trim();

        // 1. /ssh status
        if ("status".equalsIgnoreCase(sub)) {
            List<SshSessionManager.SessionInfo> sessions = sshManager.listSessions();
            if (sessions.isEmpty()) {
                writer.println(ANSI_YELLOW + "No active SSH sessions." + ANSI_RESET);
                writer.println(ANSI_DIM + "Connect with: /ssh connect <host> <username> [password] [port] [alias]" + ANSI_RESET);
            } else {
                writer.println(ANSI_BOLD + ANSI_CYAN + "Active SSH Sessions (" + sessions.size() + "):" + ANSI_RESET);
                for (var s : sessions) {
                    String statusColor = s.isConnected() ? ANSI_GREEN : ANSI_RED;
                    writer.println(ANSI_BOLD + " • Alias: '" + s.getAlias() + "'" + ANSI_RESET);
                    writer.println("   Host: " + s.getUsername() + "@" + s.getHost() + ":" + s.getPort());
                    writer.println("   Connected: " + statusColor + (s.isConnected() ? "YES" : "NO") + ANSI_RESET);
                    writer.println("   Established: " + s.getConnectedAt());
                    writer.println("   Last Used: " + s.getLastUsedAt());
                }
            }
            writer.flush();
            return;
        }

        // 2. /ssh config
        if ("config".equalsIgnoreCase(sub)) {
            SshSandboxConfig cfg = cm != null ? cm.getSshSandboxConfig() : null;
            if (cfg == null || !cfg.isValid()) {
                writer.println(ANSI_YELLOW + "No saved Sandbox SSH configuration found." + ANSI_RESET);
                writer.println(ANSI_DIM + "Use /ssh connect <host> <user> [pass] to connect and persist settings." + ANSI_RESET);
            } else {
                writer.println(ANSI_BOLD + ANSI_CYAN + "Persistent Sandbox SSH Configuration:" + ANSI_RESET);
                writer.println("   Host: " + cfg.getHost() + ":" + cfg.getPort());
                writer.println("   User: " + cfg.getUsername() + " (alias: '" + cfg.getAlias() + "')");
                writer.println("   Auth: " + cfg.getAuthType());
                if (!cfg.getPrivateKeyPath().isEmpty()) {
                    writer.println("   Key Path: " + cfg.getPrivateKeyPath());
                }
                writer.println("   Has Password: " + (cfg.hasPassword() ? ANSI_GREEN + "YES" : ANSI_YELLOW + "NO") + ANSI_RESET);
                writer.println("   Has Inline Key: " + (cfg.hasPrivateKeyContent() ? ANSI_GREEN + "YES" : ANSI_YELLOW + "NO") + ANSI_RESET);
                writer.println("   Auto-Connect: " + (cfg.isAutoConnect() ? ANSI_GREEN + "ENABLED (on startup)" : ANSI_YELLOW + "DISABLED") + ANSI_RESET);
            }
            writer.flush();
            return;
        }

        // 3. /ssh autoconnect [on|off]
        if ("autoconnect".equalsIgnoreCase(sub) || "auto-connect".equalsIgnoreCase(sub)) {
            SshSandboxConfig cfg = cm != null ? cm.getSshSandboxConfig() : null;
            if (cfg == null || !cfg.isValid()) {
                writer.println(ANSI_YELLOW + "No saved Sandbox SSH configuration found to configure auto-connect." + ANSI_RESET);
                writer.println(ANSI_DIM + "Connect first using: /ssh connect <host> <username> [password]" + ANSI_RESET);
                writer.flush();
                return;
            }

            if (args.length > 1) {
                String val = args[1].trim().toLowerCase();
                boolean enable = "on".equals(val) || "true".equals(val) || "1".equals(val) || "yes".equals(val) || "enable".equals(val);
                cfg.setAutoConnect(enable);
                cm.saveSshSandboxConfig(cfg);
                writer.println(ANSI_GREEN + "Auto-connect on startup set to: " + (enable ? "ENABLED" : "DISABLED") + ANSI_RESET);
            } else {
                writer.println("Auto-connect is currently " + (cfg.isAutoConnect() ? ANSI_GREEN + "ENABLED" : ANSI_YELLOW + "DISABLED") + ANSI_RESET);
                writer.println(ANSI_DIM + "Toggle with: /ssh autoconnect on  OR  /ssh autoconnect off" + ANSI_RESET);
            }
            writer.flush();
            return;
        }

        // 4. /ssh connect <host> <username> [password] [port] [alias]
        if ("connect".equalsIgnoreCase(sub)) {
            if (args.length < 3) {
                writer.println(ANSI_YELLOW + "Usage: /ssh connect <host> <username> [password] [port] [alias]" + ANSI_RESET);
                writer.flush();
                return;
            }
            String host = args[1];
            String username = args[2];
            String password = args.length > 3 ? args[3] : null;
            int port = 22;
            String alias = "default";

            if (args.length > 4) {
                try {
                    port = Integer.parseInt(args[4]);
                } catch (NumberFormatException e) {
                    alias = args[4];
                }
            }
            if (args.length > 5) {
                alias = args[5];
            }

            writer.println(ANSI_BLUE + "Connecting to " + username + "@" + host + ":" + port + " (alias: " + alias + ")..." + ANSI_RESET);
            writer.flush();
            try {
                var entry = sshManager.connect(host, port, username, password, null, null, null, AuthType.PASSWORD, alias, false, true);
                writer.println(ANSI_GREEN + "Connected successfully to " + entry.getUsername() + "@" + entry.getHost() + ":" + entry.getPort() + " (alias='" + entry.getAlias() + "')" + ANSI_RESET);
            } catch (Exception e) {
                writer.println(ANSI_RED + "Connection failed: " + e.getMessage() + ANSI_RESET);
            }
            writer.flush();
            return;
        }

        // 5. /ssh disconnect [alias]
        if ("disconnect".equalsIgnoreCase(sub) || "close".equalsIgnoreCase(sub)) {
            String alias = args.length > 1 ? args[1] : "default";
            boolean disconnected = sshManager.disconnect(alias);
            if (disconnected) {
                writer.println(ANSI_GREEN + "Session '" + alias + "' disconnected successfully." + ANSI_RESET);
            } else {
                writer.println(ANSI_YELLOW + "No active session found for alias '" + alias + "'." + ANSI_RESET);
            }
            writer.flush();
            return;
        }

        // 6. /ssh transfer <upload|download> <localPath> <remotePath> [alias]
        if ("transfer".equalsIgnoreCase(sub) || "sftp".equalsIgnoreCase(sub)) {
            if (args.length < 4) {
                writer.println(ANSI_YELLOW + "Usage: /ssh transfer <upload|download> <localPath> <remotePath> [alias]" + ANSI_RESET);
                writer.flush();
                return;
            }
            String action = args[1].toLowerCase();
            String localPath = args[2];
            String remotePath = args[3];
            String alias = args.length > 4 ? args[4] : "default";

            writer.println(ANSI_BLUE + "SFTP " + action.toUpperCase() + " (" + alias + "): " + localPath + " <-> " + remotePath + ANSI_RESET);
            writer.flush();
            try {
                String result;
                if ("upload".equals(action)) {
                    result = sshManager.uploadFile(alias, localPath, remotePath);
                } else if ("download".equals(action)) {
                    result = sshManager.downloadFile(alias, remotePath, localPath);
                } else {
                    writer.println(ANSI_RED + "Unknown transfer action '" + action + "'. Must be 'upload' or 'download'." + ANSI_RESET);
                    writer.flush();
                    return;
                }
                writer.println(ANSI_GREEN + result + ANSI_RESET);
            } catch (Exception e) {
                writer.println(ANSI_RED + "SFTP Error: " + e.getMessage() + ANSI_RESET);
            }
            writer.flush();
            return;
        }

        // 7. Default: Direct command execution on active SSH session (/ssh <remote bash command>)
        String command = String.join(" ", args).trim();
        String activeAlias = null;
        if (sshManager.hasActiveSession("default")) {
            activeAlias = "default";
        } else {
            for (var sess : sshManager.listSessions()) {
                if (sess.isConnected()) {
                    activeAlias = sess.getAlias();
                    break;
                }
            }
        }

        if (activeAlias == null) {
            writer.println(ANSI_YELLOW + "No active SSH session found." + ANSI_RESET);
            writer.println(ANSI_DIM + "Connect first using: /ssh connect <host> <username> [password] [port] [alias]" + ANSI_RESET);
            writer.flush();
            return;
        }

        writer.println(ANSI_BLUE + "[SSH:" + activeAlias + "] $ " + command + ANSI_RESET);
        writer.flush();
        try {
            var execResult = sshManager.executeCommand(activeAlias, command, 30);
            if (execResult.getStdout() != null && !execResult.getStdout().isEmpty()) {
                writer.print(execResult.getStdout());
                if (!execResult.getStdout().endsWith("\n")) {
                    writer.println();
                }
            }
            if (execResult.getStderr() != null && !execResult.getStderr().isEmpty()) {
                writer.print(ANSI_RED + execResult.getStderr() + ANSI_RESET);
                if (!execResult.getStderr().endsWith("\n")) {
                    writer.println();
                }
            }
            if (execResult.isSuccess()) {
                writer.println(ANSI_GREEN + "[SSH:" + activeAlias + "] Exit code: 0 (" + execResult.getDurationMs() + "ms)" + ANSI_RESET);
            } else {
                writer.println(ANSI_YELLOW + "[SSH:" + activeAlias + "] Exit code: " + execResult.getExitCode() + ANSI_RESET);
            }
        } catch (Exception e) {
            writer.println(ANSI_RED + "[SSH Execution Error] " + e.getMessage() + ANSI_RESET);
        }
        writer.flush();
    }

    private void printUsage(java.io.PrintWriter writer, SshSessionManager sshManager) {
        writer.println(ANSI_BOLD + ANSI_CYAN + "SSH Commands & Remote Execution:" + ANSI_RESET);
        writer.println("  /ssh <command>                                      - Execute bash command on active remote session");
        writer.println("  /ssh status                                         - Show active SSH sessions");
        writer.println("  /ssh config                                         - Show saved persistent sandbox SSH config");
        writer.println("  /ssh autoconnect [on|off]                           - Toggle auto-connect on application startup");
        writer.println("  /ssh connect <host> <user> [pass] [port] [alias]    - Connect to remote SSH host");
        writer.println("  /ssh disconnect [alias]                             - Disconnect SSH session");
        writer.println("  /ssh transfer <upload|download> <local> <remote>    - SFTP file transfer");
        writer.println("  /exec <command>                                     - Run command on SSH if connected, else locally");
        var active = sshManager.listSessions();
        if (!active.isEmpty()) {
            writer.println(ANSI_GREEN + "Active Sessions: " + active.size() + ANSI_RESET);
        } else {
            writer.println(ANSI_DIM + "No active SSH session." + ANSI_RESET);
        }
        writer.flush();
    }

    @Override
    public String getName() {
        return "ssh";
    }

    @Override
    public String getDescription() {
        return "Execute remote commands or manage SSH connections (/ssh <cmd>, /ssh status, /ssh config, /ssh autoconnect, /ssh connect ...)";
    }
}
