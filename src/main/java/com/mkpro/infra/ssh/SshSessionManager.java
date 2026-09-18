package com.mkpro.infra.ssh;

import com.jcraft.jsch.*;
import com.mkpro.CentralMemory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SshSessionManager manages persistent SSH and SFTP connections using JSch.
 * Supports multi-session management, password and key authentication, remote command execution,
 * SFTP file transfers, persistent credential caching in CentralMemory / MapDB, and background auto-reconnect.
 */
public class SshSessionManager {

    private static final SshSessionManager INSTANCE = new SshSessionManager();

    public static SshSessionManager getInstance() {
        return INSTANCE;
    }

    public static class SessionInfo {
        private final String alias;
        private final String host;
        private final int port;
        private final String username;
        private final Instant connectedAt;
        private final Instant lastUsedAt;
        private final boolean connected;

        public SessionInfo(String alias, String host, int port, String username,
                           Instant connectedAt, Instant lastUsedAt, boolean connected) {
            this.alias = alias;
            this.host = host;
            this.port = port;
            this.username = username;
            this.connectedAt = connectedAt;
            this.lastUsedAt = lastUsedAt;
            this.connected = connected;
        }

        public String getAlias() { return alias; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getUsername() { return username; }
        public String getUser() { return username; }
        public Instant getConnectedAt() { return connectedAt; }
        public Instant getLastUsedAt() { return lastUsedAt; }
        public boolean isConnected() { return connected; }
    }

    public static class SshSessionEntry {
        private final String alias;
        private final String host;
        private final int port;
        private final String username;
        private final Session session;
        private final Instant connectedAt;
        private volatile Instant lastUsedAt;

        public SshSessionEntry(String alias, String host, int port, String username, Session session) {
            this.alias = alias;
            this.host = host;
            this.port = port;
            this.username = username;
            this.session = session;
            this.connectedAt = Instant.now();
            this.lastUsedAt = Instant.now();
        }

        public String getAlias() { return alias; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getUsername() { return username; }
        public String getUser() { return username; }
        public Session getSession() { return session; }
        public Instant getConnectedAt() { return connectedAt; }
        public Instant getLastUsedAt() { return lastUsedAt; }
        public void updateLastUsed() { this.lastUsedAt = Instant.now(); }

        public boolean isConnected() {
            return session != null && session.isConnected();
        }

        public SessionInfo toSessionInfo() {
            return new SessionInfo(alias, host, port, username, connectedAt, lastUsedAt, isConnected());
        }
    }

    public static class CommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final long durationMs;

        public CommandResult(int exitCode, String stdout, String stderr, long durationMs) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.durationMs = durationMs;
        }

        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public long getDurationMs() { return durationMs; }
        public boolean isSuccess() { return exitCode == 0; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Exit Code: ").append(exitCode).append(" (").append(durationMs).append(" ms)\n");
            if (stdout != null && !stdout.isBlank()) {
                sb.append("── STDOUT ──\n").append(stdout.stripTrailing()).append("\n");
            }
            if (stderr != null && !stderr.isBlank()) {
                sb.append("── STDERR ──\n").append(stderr.stripTrailing()).append("\n");
            }
            return sb.toString();
        }
    }

    private final Map<String, SshSessionEntry> sessions = new ConcurrentHashMap<>();
    private final JSch jsch = new JSch();

    public SshSessionManager() {
        // Disable strict host key checking by default for automated CLI agents
        JSch.setConfig("StrictHostKeyChecking", "no");
        JSch.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
    }

    /**
     * Connect to a remote host using a saved SshSandboxConfig.
     */
    public synchronized SshSessionEntry connect(SshSandboxConfig config) throws Exception {
        if (config == null || !config.isValid()) {
            throw new IllegalArgumentException("Invalid SSH Sandbox configuration.");
        }
        return connect(
            config.getHost(),
            config.getPort(),
            config.getUsername(),
            config.getPassword(),
            config.getPrivateKeyPath(),
            config.getPrivateKeyContent(),
            config.getPassphrase(),
            config.getAuthType(),
            config.getAlias(),
            config.isAutoConnect(),
            true
        );
    }

    /**
     * Connect to a remote host and store the active session.
     * Optionally saves/updates the configuration in CentralMemory.
     */
    public synchronized SshSessionEntry connect(String host, int port, String username,
                                                String password, String privateKeyPath,
                                                String privateKeyContent, String passphrase,
                                                AuthType authType, String alias,
                                                boolean autoConnect, boolean saveConfig) throws Exception {
        String effectiveAlias = (alias == null || alias.isBlank()) ? "default" : alias.trim();
        int effectivePort = (port <= 0) ? 22 : port;
        String cleanHost = (host != null) ? host.trim() : "";
        String cleanUser = (username != null) ? username.trim() : "";
        AuthType resolvedAuth = (authType != null) ? authType : AuthType.PASSWORD;

        // Disconnect existing session with the same alias if any
        disconnect(effectiveAlias);

        // If credentials (password/keyContent/passphrase) are empty or null, check CentralMemory for saved credentials
        CentralMemory cm = CentralMemory.getInstance();
        SshSandboxConfig existingCfg = null;
        try {
            existingCfg = cm.getSshSandboxConfig();
        } catch (Exception ignored) {}

        String effectivePassword = password;
        String effectiveKeyContent = privateKeyContent;
        String effectivePassphrase = passphrase;
        String effectiveKeyPath = privateKeyPath;

        if (existingCfg != null && existingCfg.isValid()
                && existingCfg.getHost().equalsIgnoreCase(cleanHost)
                && existingCfg.getUsername().equals(cleanUser)) {
            if ((effectivePassword == null || effectivePassword.isBlank()) && existingCfg.hasPassword()) {
                effectivePassword = existingCfg.getPassword();
            }
            if ((effectiveKeyContent == null || effectiveKeyContent.isBlank()) && existingCfg.hasPrivateKeyContent()) {
                effectiveKeyContent = existingCfg.getPrivateKeyContent();
            }
            if ((effectivePassphrase == null || effectivePassphrase.isBlank()) && existingCfg.hasPassphrase()) {
                effectivePassphrase = existingCfg.getPassphrase();
            }
            if ((effectiveKeyPath == null || effectiveKeyPath.isBlank()) && !existingCfg.getPrivateKeyPath().isBlank()) {
                effectiveKeyPath = existingCfg.getPrivateKeyPath();
            }
        }

        // Configure authentication
        if (resolvedAuth == AuthType.KEY_FILE && effectiveKeyPath != null && !effectiveKeyPath.isBlank()) {
            if (effectivePassphrase != null && !effectivePassphrase.isBlank()) {
                jsch.addIdentity(effectiveKeyPath, effectivePassphrase);
            } else {
                jsch.addIdentity(effectiveKeyPath);
            }
        } else if (resolvedAuth == AuthType.KEY_CONTENT && effectiveKeyContent != null && !effectiveKeyContent.isBlank()) {
            byte[] prvkey = effectiveKeyContent.getBytes(StandardCharsets.UTF_8);
            byte[] pass = (effectivePassphrase != null && !effectivePassphrase.isBlank()) ? effectivePassphrase.getBytes(StandardCharsets.UTF_8) : null;
            jsch.addIdentity("inline-key-" + effectiveAlias, prvkey, null, pass);
        }

        Session session = jsch.getSession(cleanUser, cleanHost, effectivePort);
        if (effectivePassword != null && !effectivePassword.isBlank()) {
            session.setPassword(effectivePassword);
        }

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.setTimeout(15000); // 15s socket timeout
        session.connect(15000);

        SshSessionEntry entry = new SshSessionEntry(effectiveAlias, cleanHost, effectivePort, cleanUser, session);
        sessions.put(effectiveAlias, entry);

        // If requested, persist config securely into CentralMemory / MapDB
        if (saveConfig) {
            try {
                SshSandboxConfig newCfg = (existingCfg != null && existingCfg.getHost().equalsIgnoreCase(cleanHost) && existingCfg.getUsername().equals(cleanUser))
                        ? existingCfg
                        : new SshSandboxConfig();
                newCfg.setHost(cleanHost);
                newCfg.setPort(effectivePort);
                newCfg.setUsername(cleanUser);
                newCfg.setAlias(effectiveAlias);
                newCfg.setAuthType(resolvedAuth);
                newCfg.setPrivateKeyPath(effectiveKeyPath != null ? effectiveKeyPath : "");
                if (effectivePassword != null && !effectivePassword.isBlank()) {
                    newCfg.setPassword(effectivePassword);
                }
                if (effectiveKeyContent != null && !effectiveKeyContent.isBlank()) {
                    newCfg.setPrivateKeyContent(effectiveKeyContent);
                }
                if (effectivePassphrase != null && !effectivePassphrase.isBlank()) {
                    newCfg.setPassphrase(effectivePassphrase);
                }
                newCfg.setAutoConnect(autoConnect);
                cm.saveSshSandboxConfig(newCfg);
            } catch (Exception e) {
                System.err.println("[SshSessionManager] Warning: failed to save SSH config to CentralMemory: " + e.getMessage());
            }
        }

        return entry;
    }

    /**
     * Connect to a remote host and store the active session.
     */
    public synchronized SshSessionEntry connect(String host, int port, String username,
                                                String password, String privateKeyPath,
                                                String privateKeyContent, String passphrase,
                                                AuthType authType, String alias) throws Exception {
        return connect(host, port, username, password, privateKeyPath, privateKeyContent, passphrase, authType, alias, false, true);
    }

    /**
     * Automatically connect to the saved Sandbox SSH environment in the background
     * if autoConnect is enabled in CentralMemory.
     */
    public void autoConnect() {
        Thread t = new Thread(() -> {
            try {
                CentralMemory cm = CentralMemory.getInstance();
                SshSandboxConfig cfg = cm.getSshSandboxConfig();
                if (cfg == null || !cfg.isValid() || !cfg.isAutoConnect()) {
                    return;
                }

                String alias = cfg.getAlias();
                if (hasActiveSession(alias)) {
                    return; // Already connected
                }

                System.out.println("\u001b[34m[SSH] Auto-connecting to sandbox " + cfg.getUsername() + "@" +
                        cfg.getHost() + ":" + cfg.getPort() + " (alias: " + alias + ")... \u001b[0m");

                connect(cfg);

                System.out.println("\u001b[32m[SSH] Sandbox auto-connected successfully (alias: " + alias + ").\u001b[0m");
            } catch (Exception e) {
                System.out.println("\u001b[33m[SSH] Sandbox auto-connect skipped/failed: " + e.getMessage() + "\u001b[0m");
            }
        }, "ssh-sandbox-autoconnect");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Execute a shell command on an active SSH session.
     */
    public CommandResult executeCommand(String alias, String command, int timeoutSeconds) throws Exception {
        SshSessionEntry entry = getSessionEntry(alias);
        if (entry == null || !entry.isConnected()) {
            throw new IllegalStateException("No active SSH session found for alias: '" + (alias != null ? alias : "default") + "'. Please connect first.");
        }

        entry.updateLastUsed();
        Session session = entry.getSession();
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setInputStream(null);

        ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();
        channel.setOutputStream(stdoutStream);
        channel.setErrStream(stderrStream);

        long start = System.currentTimeMillis();
        channel.connect(5000);

        int timeoutMs = (timeoutSeconds <= 0 ? 30 : timeoutSeconds) * 1000;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (!channel.isClosed()) {
            if (System.currentTimeMillis() > deadline) {
                channel.disconnect();
                throw new java.util.concurrent.TimeoutException("Command timed out after " + timeoutSeconds + " seconds: " + command);
            }
            Thread.sleep(50);
        }

        int exitCode = channel.getExitStatus();
        channel.disconnect();
        long duration = System.currentTimeMillis() - start;

        String stdout = stdoutStream.toString(StandardCharsets.UTF_8);
        String stderr = stderrStream.toString(StandardCharsets.UTF_8);

        return new CommandResult(exitCode, stdout, stderr, duration);
    }

    /**
     * Upload a local file to a remote destination via SFTP.
     */
    public String uploadFile(String alias, String localPathStr, String remotePathStr) throws Exception {
        SshSessionEntry entry = getSessionEntry(alias);
        if (entry == null || !entry.isConnected()) {
            throw new IllegalStateException("No active SSH session found for alias: '" + (alias != null ? alias : "default") + "'. Please connect first.");
        }

        Path localPath = Paths.get(localPathStr);
        if (!Files.exists(localPath)) {
            throw new FileNotFoundException("Local file does not exist: " + localPathStr);
        }

        entry.updateLastUsed();
        Session session = entry.getSession();
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(5000);

        try {
            // Ensure remote parent directories exist if needed
            String remoteDir = remotePathStr.contains("/") ? remotePathStr.substring(0, remotePathStr.lastIndexOf('/')) : "";
            if (!remoteDir.isBlank()) {
                ensureRemoteDir(sftp, remoteDir);
            }

            try (InputStream in = Files.newInputStream(localPath)) {
                sftp.put(in, remotePathStr, ChannelSftp.OVERWRITE);
            }
            long size = Files.size(localPath);
            return String.format("Successfully uploaded '%s' (%d bytes) to remote '%s'", localPathStr, size, remotePathStr);
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * Download a remote file to a local destination via SFTP.
     */
    public String downloadFile(String alias, String remotePathStr, String localPathStr) throws Exception {
        SshSessionEntry entry = getSessionEntry(alias);
        if (entry == null || !entry.isConnected()) {
            throw new IllegalStateException("No active SSH session found for alias: '" + (alias != null ? alias : "default") + "'. Please connect first.");
        }

        entry.updateLastUsed();
        Session session = entry.getSession();
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(5000);

        try {
            Path localPath = Paths.get(localPathStr);
            if (localPath.getParent() != null) {
                Files.createDirectories(localPath.getParent());
            }

            try (OutputStream out = Files.newOutputStream(localPath)) {
                sftp.get(remotePathStr, out);
            }
            long size = Files.size(localPath);
            return String.format("Successfully downloaded remote '%s' to '%s' (%d bytes)", remotePathStr, localPathStr, size);
        } finally {
            sftp.disconnect();
        }
    }

    private void ensureRemoteDir(ChannelSftp sftp, String remoteDir) {
        String[] parts = remoteDir.split("/");
        StringBuilder path = new StringBuilder();
        if (remoteDir.startsWith("/")) {
            path.append("/");
        }
        for (String part : parts) {
            if (part.isBlank()) continue;
            path.append(part).append("/");
            try {
                sftp.cd(path.toString());
            } catch (SftpException e) {
                try {
                    sftp.mkdir(path.toString());
                } catch (SftpException ignored) {}
            }
        }
    }

    /**
     * Disconnect a session by alias.
     */
    public synchronized boolean disconnect(String alias) {
        String key = (alias == null || alias.isBlank()) ? "default" : alias.trim();
        SshSessionEntry entry = sessions.remove(key);
        if (entry != null && entry.getSession() != null) {
            try {
                if (entry.getSession().isConnected()) {
                    entry.getSession().disconnect();
                }
            } catch (Exception ignored) {}
            return true;
        }
        return false;
    }

    /**
     * Disconnect all active sessions.
     */
    public synchronized void disconnectAll() {
        for (String key : new ArrayList<>(sessions.keySet())) {
            disconnect(key);
        }
    }

    public boolean hasActiveSession(String alias) {
        SshSessionEntry entry = getSessionEntry(alias);
        return entry != null && entry.isConnected();
    }

    public boolean hasActiveSessions() {
        for (SshSessionEntry entry : sessions.values()) {
            if (entry != null && entry.isConnected()) {
                return true;
            }
        }
        return false;
    }

    public SessionInfo getSessionInfo(String alias) {
        SshSessionEntry entry = getSessionEntry(alias);
        return entry != null ? entry.toSessionInfo() : null;
    }

    public SshSessionEntry getSessionEntry(String alias) {
        String key = (alias == null || alias.isBlank()) ? "default" : alias.trim();
        return sessions.get(key);
    }

    public List<SessionInfo> listSessions() {
        List<SessionInfo> list = new ArrayList<>();
        for (SshSessionEntry entry : sessions.values()) {
            list.add(entry.toSessionInfo());
        }
        return list;
    }
}
