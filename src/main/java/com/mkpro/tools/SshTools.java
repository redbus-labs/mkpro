package com.mkpro.tools;

import static com.mkpro.ui.AnsiColors.*;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.mkpro.infra.ssh.AuthType;
import com.mkpro.infra.ssh.SshSessionManager;
import io.reactivex.rxjava3.core.Single;

import java.util.*;

/**
 * SshTools exposes the persistent remote SSH tool suite for Ubuntu/Linux system administration:
 * - ssh_connect: Establish persistent SSH session to remote host
 * - ssh_exec: Execute remote bash/shell commands with real-time output and exit codes
 * - ssh_file_transfer: SFTP upload or download of files and directories
 * - ssh_disconnect: Terminate an active SSH session
 * - ssh_status: Inspect active sessions and connection states
 */
public class SshTools {

    private static final SshSessionManager manager = SshSessionManager.getInstance();

    /**
     * Tool to connect to a remote host.
     */
    public static BaseTool createConnectTool() {
        return new BaseTool(
            "ssh_connect",
            "Connects to a remote Linux/Ubuntu host, sandbox, or server via SSH. Supports password authentication, " +
            "private key file path, or inline private key content. Establishes a persistent session alias " +
            "used for subsequent command executions and file transfers."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.<String, Schema>builder()
                            .put("host", Schema.builder().type("STRING").description("The remote server hostname or IP address.").build())
                            .put("username", Schema.builder().type("STRING").description("SSH username (e.g., 'ubuntu', 'root', 'deploy').").build())
                            .put("port", Schema.builder().type("INTEGER").description("SSH port number (default: 22).").build())
                            .put("password", Schema.builder().type("STRING").description("Password for authentication (if using password auth).").build())
                            .put("privateKeyPath", Schema.builder().type("STRING").description("Local file path to private key (e.g., '~/.ssh/id_rsa').").build())
                            .put("privateKeyContent", Schema.builder().type("STRING").description("Raw PEM/OpenSSH private key content string.").build())
                            .put("passphrase", Schema.builder().type("STRING").description("Passphrase for encrypted private key (if any).").build())
                            .put("authType", Schema.builder().type("STRING").description("Auth type: 'PASSWORD', 'KEY_FILE', 'KEY_CONTENT' (default auto-detected).").build())
                            .put("alias", Schema.builder().type("STRING").description("Session alias/identifier (optional, default: 'default').").build())
                            .put("sessionAlias", Schema.builder().type("STRING").description("Session alias/identifier (optional, default: 'default').").build())
                            .build())
                        .required(ImmutableList.of("host", "username"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String host = (String) args.get("host");
                    String username = (String) args.get("username");
                    
                    int port = 22;
                    if (args.get("port") instanceof Number) {
                        port = ((Number) args.get("port")).intValue();
                    } else if (args.get("port") instanceof String) {
                        try {
                            port = Integer.parseInt((String) args.get("port"));
                        } catch (NumberFormatException ignored) {}
                    }

                    String password = (String) args.get("password");
                    String privateKeyPath = (String) args.get("privateKeyPath");
                    String privateKeyContent = (String) args.get("privateKeyContent");
                    String passphrase = (String) args.get("passphrase");
                    String authTypeStr = (String) args.get("authType");
                    String sessionAlias = (String) args.get("sessionAlias");
                    if (sessionAlias == null || sessionAlias.isBlank()) {
                        sessionAlias = (String) args.get("alias");
                    }
                    if (sessionAlias == null || sessionAlias.isBlank()) {
                        sessionAlias = "default";
                    }

                    AuthType authType;
                    if (authTypeStr != null && !authTypeStr.isBlank()) {
                        authType = AuthType.fromString(authTypeStr);
                    } else if (privateKeyContent != null && !privateKeyContent.isBlank()) {
                        authType = AuthType.KEY_CONTENT;
                    } else if (privateKeyPath != null && !privateKeyPath.isBlank()) {
                        authType = AuthType.KEY_FILE;
                    } else {
                        authType = AuthType.PASSWORD;
                    }

                    System.out.println(ANSI_BLUE + "[SSH] Connecting to " + username + "@" + host + ":" + port + 
                        " (alias: " + sessionAlias + ", auth: " + authType + ")..." + ANSI_RESET);

                    try {
                        var entry = manager.connect(host, port, username, password, privateKeyPath, 
                            privateKeyContent, passphrase, authType, sessionAlias);
                        
                        String result = String.format("Connected successfully to %s@%s:%d (sessionAlias='%s')",
                            entry.getUsername(), entry.getHost(), entry.getPort(), entry.getAlias());
                        System.out.println(ANSI_GREEN + "[SSH] " + result + ANSI_RESET);
                        return Collections.singletonMap("result", result);
                    } catch (Exception e) {
                        String err = "Failed to connect to " + username + "@" + host + ":" + port + ": " + e.getMessage();
                        System.err.println(ANSI_RED + "[SSH] " + err + ANSI_RESET);
                        return Collections.singletonMap("error", err);
                    }
                });
            }
        };
    }

    /**
     * Tool to execute a shell command on an active SSH session.
     */
    public static BaseTool createExecTool() {
        return new BaseTool(
            "ssh_exec",
            "Executes a bash/shell command on an active remote SSH session (remote machine, sandbox, ubuntu, or remote server). " +
            "When user prompts mention running commands on a remote machine, sandbox, ubuntu, or remote server, invoke this tool. " +
            "Returns exit status code, standard output (stdout), and error output (stderr). " +
            "Recommended for Ubuntu administrative commands, diagnostics, log reading, systemctl service management, and remote workflows."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.<String, Schema>builder()
                            .put("command", Schema.builder().type("STRING").description("The shell/bash command to run on remote Ubuntu host, sandbox, or server.").build())
                            .put("alias", Schema.builder().type("STRING").description("Session alias/identifier (optional, default: 'default').").build())
                            .put("sessionAlias", Schema.builder().type("STRING").description("Session alias/identifier (optional, default: 'default').").build())
                            .put("timeoutSeconds", Schema.builder().type("INTEGER").description("Execution timeout in seconds (default: 30).").build())
                            .build())
                        .required(ImmutableList.of("command"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String command = (String) args.get("command");
                    String sessionAlias = (String) args.get("sessionAlias");
                    if (sessionAlias == null || sessionAlias.isBlank()) {
                        sessionAlias = (String) args.get("alias");
                    }
                    if (sessionAlias == null || sessionAlias.isBlank()) {
                        sessionAlias = "default";
                    }

                    int timeoutSeconds = 30;
                    if (args.get("timeoutSeconds") instanceof Number) {
                        timeoutSeconds = ((Number) args.get("timeoutSeconds")).intValue();
                    } else if (args.get("timeoutSeconds") instanceof String) {
                        try {
                            timeoutSeconds = Integer.parseInt((String) args.get("timeoutSeconds"));
                        } catch (NumberFormatException ignored) {}
                    }

                    System.out.println(ANSI_BLUE + "[SSH:" + sessionAlias + "] $ " + command + ANSI_RESET);

                    try {
                        var result = manager.executeCommand(sessionAlias, command, timeoutSeconds);
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("exitCode", result.getExitCode());
                        response.put("success", result.isSuccess());
                        response.put("stdout", result.getStdout());
                        response.put("stderr", result.getStderr());
                        response.put("durationMs", result.getDurationMs());
                        response.put("formattedOutput", result.toString());

                        if (result.isSuccess()) {
                            System.out.println(ANSI_GREEN + "[SSH:" + sessionAlias + "] Command finished (exit 0, " + result.getDurationMs() + "ms)" + ANSI_RESET);
                        } else {
                            System.out.println(ANSI_YELLOW + "[SSH:" + sessionAlias + "] Command finished with exit " + result.getExitCode() + ANSI_RESET);
                        }
                        return response;
                    } catch (Exception e) {
                        String err = "SSH Execution Error: " + e.getMessage();
                        System.err.println(ANSI_RED + "[SSH:" + sessionAlias + "] " + err + ANSI_RESET);
                        return Collections.singletonMap("error", err);
                    }
                });
            }
        };
    }

    /**
     * Tool to transfer files between local machine and remote host via SFTP.
     */
    public static BaseTool createFileTransferTool() {
        return new BaseTool(
            "ssh_file_transfer",
            "Transfers files between the local system and the remote server or sandbox using SFTP. " +
            "Supports action 'upload' (local to remote) and 'download' (remote to local)."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.<String, Schema>builder()
                            .put("action", Schema.builder().type("STRING").description("Action: 'upload' (local->remote) or 'download' (remote->local).").build())
                            .put("localPath", Schema.builder().type("STRING").description("Path on the local file system.").build())
                            .put("remotePath", Schema.builder().type("STRING").description("Path on the remote Ubuntu server or sandbox.").build())
                            .put("alias", Schema.builder().type("STRING").description("Session alias/identifier (optional, default: 'default').").build())
                            .put("sessionAlias", Schema.builder().type("STRING").description("Session alias/identifier (optional, default: 'default').").build())
                            .build())
                        .required(ImmutableList.of("action", "localPath", "remotePath"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String action = ((String) args.get("action")).trim().toLowerCase();
                    String localPath = (String) args.get("localPath");
                    String remotePath = (String) args.get("remotePath");
                    String sessionAlias = (String) args.get("sessionAlias");
                    if (sessionAlias == null || sessionAlias.isBlank()) {
                        sessionAlias = (String) args.get("alias");
                    }
                    if (sessionAlias == null || sessionAlias.isBlank()) {
                        sessionAlias = "default";
                    }

                    System.out.println(ANSI_BLUE + "[SSH SFTP:" + sessionAlias + "] " + action.toUpperCase() + 
                        ": " + localPath + " <-> " + remotePath + ANSI_RESET);

                    try {
                        String message;
                        if ("upload".equals(action)) {
                            message = manager.uploadFile(sessionAlias, localPath, remotePath);
                        } else if ("download".equals(action)) {
                            message = manager.downloadFile(sessionAlias, remotePath, localPath);
                        } else {
                            return Collections.singletonMap("error", "Unknown action '" + action + "'. Must be 'upload' or 'download'.");
                        }
                        System.out.println(ANSI_GREEN + "[SSH SFTP:" + sessionAlias + "] " + message + ANSI_RESET);
                        return Collections.singletonMap("result", message);
                    } catch (Exception e) {
                        String err = "SFTP Transfer Error (" + action + "): " + e.getMessage();
                        System.err.println(ANSI_RED + "[SSH SFTP:" + sessionAlias + "] " + err + ANSI_RESET);
                        return Collections.singletonMap("error", err);
                    }
                });
            }
        };
    }

    /**
     * Tool to disconnect an active SSH session.
     */
    public static BaseTool createDisconnectTool() {
        return new BaseTool(
            "ssh_disconnect",
            "Closes and terminates an active SSH session by session alias (default: 'default')."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                            "alias", Schema.builder().type("STRING").description("Session alias to disconnect (optional, default: 'default').").build(),
                            "sessionAlias", Schema.builder().type("STRING").description("Session alias to disconnect (optional, default: 'default').").build()
                        ))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String sessionAlias = (String) args.get("sessionAlias");
                    if (sessionAlias == null || sessionAlias.isBlank()) {
                        sessionAlias = (String) args.get("alias");
                    }
                    if (sessionAlias == null || sessionAlias.isBlank()) {
                        sessionAlias = "default";
                    }

                    boolean disconnected = manager.disconnect(sessionAlias);
                    String result = disconnected 
                        ? "Session '" + sessionAlias + "' disconnected successfully."
                        : "No active session found for alias '" + sessionAlias + "'.";
                    System.out.println(ANSI_BLUE + "[SSH] " + result + ANSI_RESET);
                    return Collections.singletonMap("result", result);
                });
            }
        };
    }

    /**
     * Tool to list active SSH sessions and status.
     */
    public static BaseTool createStatusTool() {
        return new BaseTool(
            "ssh_status",
            "Lists all currently open and persistent SSH connections with their status, uptime, host, and user."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Collections.emptyMap())
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    var sessions = manager.listSessions();
                    if (sessions.isEmpty()) {
                        return Collections.singletonMap("result", "No active SSH sessions.");
                    }

                    StringBuilder sb = new StringBuilder("Active SSH Sessions (" + sessions.size() + "):\n");
                    for (var s : sessions) {
                        sb.append("• Alias: '").append(s.getAlias()).append("'\n")
                          .append("  Host: ").append(s.getUsername()).append("@").append(s.getHost()).append(":").append(s.getPort()).append("\n")
                          .append("  Connected: ").append(s.isConnected() ? "YES" : "NO").append("\n")
                          .append("  Established: ").append(s.getConnectedAt()).append("\n")
                          .append("  Last Used: ").append(s.getLastUsedAt()).append("\n");
                    }
                    return Collections.singletonMap("result", sb.toString().trim());
                });
            }
        };
    }

    /**
     * Returns the full SSH tool suite.
     */
    public static List<BaseTool> createSuite() {
        return List.of(
            createConnectTool(),
            createExecTool(),
            createFileTransferTool(),
            createDisconnectTool(),
            createStatusTool()
        );
    }
}
