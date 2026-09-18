package com.mkpro.security;

import com.mkpro.utils.PathUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Provides AES-GCM encryption and decryption for sensitive persistent data
 * such as SSH sandbox passwords, private keys, and passphrases.
 * 
 * Uses a machine-local secret key stored securely in the user's .mkpro configuration directory.
 */
public final class CredentialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final int KEY_LENGTH_BYTE = 16; // 128-bit AES
    private static final String PREFIX = "ENC:";

    private static volatile byte[] masterKey = null;
    private static final Object LOCK = new Object();

    private CredentialCipher() {}

    /**
     * Encrypt a plaintext credential with AES-GCM.
     * Returns string formatted as "ENC:<base64(iv + ciphertext)>".
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (isEncrypted(plaintext)) {
            return plaintext; // Already encrypted
        }
        try {
            byte[] keyBytes = getOrInitKey();
            byte[] iv = new byte[IV_LENGTH_BYTE];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));

            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            System.err.println("[CredentialCipher] Encryption failed: " + e.getMessage());
            return plaintext;
        }
    }

    /**
     * Decrypt an encrypted credential ("ENC:...").
     * If the text is unencrypted (doesn't start with "ENC:"), returns it as-is.
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        if (!isEncrypted(encryptedText)) {
            return encryptedText; // Plaintext fallback
        }
        try {
            byte[] keyBytes = getOrInitKey();
            String payload = encryptedText.substring(PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(payload);
            if (combined.length <= IV_LENGTH_BYTE) {
                return "";
            }

            byte[] iv = new byte[IV_LENGTH_BYTE];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTE);

            byte[] cipherText = new byte[combined.length - IV_LENGTH_BYTE];
            System.arraycopy(combined, IV_LENGTH_BYTE, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));

            byte[] decrypted = cipher.doFinal(cipherText);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[CredentialCipher] Decryption failed: " + e.getMessage());
            return "";
        }
    }

    public static boolean isEncrypted(String str) {
        return str != null && str.startsWith(PREFIX);
    }

    private static byte[] getOrInitKey() {
        if (masterKey != null) {
            return masterKey;
        }
        synchronized (LOCK) {
            if (masterKey != null) {
                return masterKey;
            }
            masterKey = loadOrCreateKeyFile();
            return masterKey;
        }
    }

    private static byte[] loadOrCreateKeyFile() {
        try {
            Path secretDir = PathUtils.getConfigDir();
            Path secretFile = secretDir.resolve(".sandbox_secret");

            if (Files.exists(secretFile)) {
                byte[] key = Files.readAllBytes(secretFile);
                if (key.length == KEY_LENGTH_BYTE) {
                    return key;
                }
            }

            // Create new random key
            byte[] key = new byte[KEY_LENGTH_BYTE];
            new SecureRandom().nextBytes(key);

            Files.createDirectories(secretDir);
            Files.write(secretFile, key);

            // Restrict file permissions on POSIX systems
            if (PathUtils.isLinux() || PathUtils.isMac()) {
                try {
                    Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                    Files.setPosixFilePermissions(secretFile, perms);
                } catch (Exception ignored) {}
            }

            return key;
        } catch (IOException e) {
            System.err.println("[CredentialCipher] Warning: could not access key file, using fallback key: " + e.getMessage());
            // Deterministic fallback derived from user home & username
            byte[] fallback = new byte[KEY_LENGTH_BYTE];
            byte[] seed = (PathUtils.getUserHome() + System.getProperty("user.name", "mkpro")).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < KEY_LENGTH_BYTE; i++) {
                fallback[i] = (byte) (seed[i % seed.length] ^ (0x5A + i * 7));
            }
            return fallback;
        }
    }
}
