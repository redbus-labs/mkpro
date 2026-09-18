package com.mkpro.infra.ssh;

import com.mkpro.security.CredentialCipher;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent configuration model for the Sandbox SSH environment.
 * Stored securely in CentralMemory / MapDB with encrypted credentials.
 */
public class SshSandboxConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String host = "";
    private int port = 22;
    private String username = "";
    private String alias = "default";
    private AuthType authType = AuthType.PASSWORD;
    private String privateKeyPath = "";
    private String encryptedPassword = "";
    private String encryptedPrivateKeyContent = "";
    private String encryptedPassphrase = "";
    private boolean autoConnect = false;
    private long updatedAt = System.currentTimeMillis();

    public SshSandboxConfig() {}

    public SshSandboxConfig(String host, int port, String username, String alias, AuthType authType) {
        this.host = host != null ? host.trim() : "";
        this.port = port > 0 ? port : 22;
        this.username = username != null ? username.trim() : "";
        this.alias = (alias != null && !alias.isBlank()) ? alias.trim() : "default";
        this.authType = authType != null ? authType : AuthType.PASSWORD;
        this.updatedAt = System.currentTimeMillis();
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host != null ? host.trim() : ""; }

    public int getPort() { return port > 0 ? port : 22; }
    public void setPort(int port) { this.port = port > 0 ? port : 22; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username != null ? username.trim() : ""; }

    public String getAlias() { return (alias != null && !alias.isBlank()) ? alias.trim() : "default"; }
    public void setAlias(String alias) { this.alias = (alias != null && !alias.isBlank()) ? alias.trim() : "default"; }

    public AuthType getAuthType() { return authType != null ? authType : AuthType.PASSWORD; }
    public void setAuthType(AuthType authType) { this.authType = authType != null ? authType : AuthType.PASSWORD; }

    public String getPrivateKeyPath() { return privateKeyPath != null ? privateKeyPath.trim() : ""; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath != null ? privateKeyPath.trim() : ""; }

    public String getEncryptedPassword() { return encryptedPassword != null ? encryptedPassword : ""; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public String getEncryptedPrivateKeyContent() { return encryptedPrivateKeyContent != null ? encryptedPrivateKeyContent : ""; }
    public void setEncryptedPrivateKeyContent(String encryptedPrivateKeyContent) { this.encryptedPrivateKeyContent = encryptedPrivateKeyContent; }

    public String getEncryptedPassphrase() { return encryptedPassphrase != null ? encryptedPassphrase : ""; }
    public void setEncryptedPassphrase(String encryptedPassphrase) { this.encryptedPassphrase = encryptedPassphrase; }

    /**
     * Decrypt and return the plaintext password.
     */
    public String getPassword() {
        return CredentialCipher.decrypt(encryptedPassword);
    }

    /**
     * Encrypt and store the plaintext password.
     */
    public void setPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            this.encryptedPassword = "";
        } else {
            this.encryptedPassword = CredentialCipher.encrypt(rawPassword);
        }
    }

    /**
     * Decrypt and return the plaintext inline private key content.
     */
    public String getPrivateKeyContent() {
        return CredentialCipher.decrypt(encryptedPrivateKeyContent);
    }

    /**
     * Encrypt and store the plaintext private key content.
     */
    public void setPrivateKeyContent(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            this.encryptedPrivateKeyContent = "";
        } else {
            this.encryptedPrivateKeyContent = CredentialCipher.encrypt(rawContent);
        }
    }

    /**
     * Decrypt and return the plaintext passphrase.
     */
    public String getPassphrase() {
        return CredentialCipher.decrypt(encryptedPassphrase);
    }

    /**
     * Encrypt and store the plaintext passphrase.
     */
    public void setPassphrase(String rawPassphrase) {
        if (rawPassphrase == null || rawPassphrase.isBlank()) {
            this.encryptedPassphrase = "";
        } else {
            this.encryptedPassphrase = CredentialCipher.encrypt(rawPassphrase);
        }
    }

    public boolean isAutoConnect() { return autoConnect; }
    public void setAutoConnect(boolean autoConnect) { this.autoConnect = autoConnect; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public boolean hasPassword() {
        return encryptedPassword != null && !encryptedPassword.isBlank();
    }

    public boolean hasPrivateKeyContent() {
        return encryptedPrivateKeyContent != null && !encryptedPrivateKeyContent.isBlank();
    }

    public boolean hasPassphrase() {
        return encryptedPassphrase != null && !encryptedPassphrase.isBlank();
    }

    public boolean isValid() {
        return host != null && !host.isBlank() && username != null && !username.isBlank();
    }

    /**
     * Safe representation for JSON serialization without exposing sensitive secrets.
     */
    public Map<String, Object> toSafeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("configured", isValid());
        map.put("host", host != null ? host : "");
        map.put("port", getPort());
        map.put("username", username != null ? username : "");
        map.put("alias", getAlias());
        map.put("authType", getAuthType().name());
        map.put("privateKeyPath", getPrivateKeyPath());
        map.put("hasPassword", hasPassword());
        map.put("hasPrivateKeyContent", hasPrivateKeyContent());
        map.put("hasPassphrase", hasPassphrase());
        map.put("autoConnect", autoConnect);
        map.put("updatedAt", updatedAt);
        return map;
    }

    @Override
    public String toString() {
        return "SshSandboxConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", username='" + username + '\'' +
                ", alias='" + alias + '\'' +
                ", authType=" + authType +
                ", autoConnect=" + autoConnect +
                ", hasPassword=" + hasPassword() +
                ", hasKey=" + (!getPrivateKeyPath().isEmpty() || hasPrivateKeyContent()) +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
