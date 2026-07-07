package com.mkpro.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * MessageAuthenticator provides HMAC-SHA256 signing and verification for P2P messages.
 * Uses a shared secret stored in ~/.mkpro/cluster_secret.key.
 * 
 * Peers must share this key file to communicate (manual copy or pairing mechanism).
 */
public class MessageAuthenticator {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String KEY_FILE_NAME = "cluster_secret.key";
    private static final int KEY_SIZE_BYTES = 32; // 256-bit key

    private final byte[] secretKey;

    private static volatile MessageAuthenticator instance;

    private MessageAuthenticator(byte[] secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * Get or create the singleton instance.
     * Loads the key from ~/.mkpro/cluster_secret.key, creating it if it doesn't exist.
     */
    public static synchronized MessageAuthenticator getInstance() {
        if (instance == null) {
            try {
                byte[] key = loadOrCreateKey();
                instance = new MessageAuthenticator(key);
            } catch (IOException e) {
                System.err.println("[MessageAuthenticator] WARNING: Failed to load/create key: " + e.getMessage());
                System.err.println("[MessageAuthenticator] P2P authentication is DISABLED for this session.");
                // Return a disabled authenticator that accepts everything
                instance = new MessageAuthenticator(null);
            }
        }
        return instance;
    }

    /**
     * Initialize with a specific key (used for testing or manual key injection).
     */
    public static synchronized MessageAuthenticator initialize(byte[] key) {
        instance = new MessageAuthenticator(key);
        return instance;
    }

    /**
     * Sign a message payload, returning the Base64-encoded HMAC.
     *
     * @param payload The message content to sign (typically the JSON body without the signature field)
     * @return Base64-encoded HMAC-SHA256 signature, or null if authentication is disabled
     */
    public String sign(String payload) {
        if (secretKey == null) return null;
        
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey, ALGORITHM);
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            System.err.println("[MessageAuthenticator] Signing failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Verify a message signature.
     *
     * @param payload The message content (same string that was signed)
     * @param signature The Base64-encoded signature to verify
     * @return true if the signature is valid, false otherwise
     */
    public boolean verify(String payload, String signature) {
        if (secretKey == null) return true; // Disabled mode — accept everything
        if (signature == null || signature.isEmpty()) return false;

        String expected = sign(payload);
        if (expected == null) return false;

        // Constant-time comparison to prevent timing attacks
        return constantTimeEquals(expected, signature);
    }

    /**
     * Check if authentication is enabled (key was loaded successfully).
     */
    public boolean isEnabled() {
        return secretKey != null;
    }

    /**
     * Get the path to the key file.
     */
    public static Path getKeyFilePath() {
        return Paths.get(System.getProperty("user.home"), ".mkpro", KEY_FILE_NAME);
    }

    /**
     * Regenerate the cluster secret. Existing peers will need the new key.
     */
    public static synchronized void regenerateKey() throws IOException {
        byte[] newKey = generateKey();
        saveKey(newKey);
        instance = new MessageAuthenticator(newKey);
    }

    // --- Private helpers ---

    private static byte[] loadOrCreateKey() throws IOException {
        Path keyPath = getKeyFilePath();

        if (Files.exists(keyPath)) {
            String encoded = Files.readString(keyPath).trim();
            return Base64.getDecoder().decode(encoded);
        }

        // Create new key
        byte[] key = generateKey();
        saveKey(key);
        System.out.println("[MessageAuthenticator] Generated new cluster secret at: " + keyPath);
        System.out.println("[MessageAuthenticator] Share this file with peers for authenticated communication.");
        return key;
    }

    private static byte[] generateKey() {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[KEY_SIZE_BYTES];
        random.nextBytes(key);
        return key;
    }

    private static void saveKey(byte[] key) throws IOException {
        Path keyPath = getKeyFilePath();
        Path parent = keyPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        String encoded = Base64.getEncoder().encodeToString(key);
        Files.writeString(keyPath, encoded);
    }

    /**
     * Constant-time string comparison to prevent timing side-channels.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
