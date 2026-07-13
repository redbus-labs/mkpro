package com.mkpro.infra.network.security;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2PAuditLog records all P2P network events for security monitoring.
 * Logs to ~/.mkpro/p2p_audit.log with timestamp, source, event type, and result.
 *
 * Also maintains a connection whitelist — only approved IPs/peers can connect.
 * Whitelist stored in ~/.mkpro/p2p_whitelist.txt (one IP or peer-ID per line).
 * Empty whitelist = accept all (open mode).
 */
public class P2PAuditLog {

    private static final String ANSI_RED_BOLD = "\u001b[1;31m";
    private static final String ANSI_YELLOW = "\u001b[33m";
    private static final String ANSI_GREEN = "\u001b[32m";
    private static final String ANSI_RESET = "\u001b[0m";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path AUDIT_LOG_PATH = Paths.get(System.getProperty("user.home"), ".mkpro", "p2p_audit.log");
    private static final Path WHITELIST_PATH = Paths.get(System.getProperty("user.home"), ".mkpro", "p2p_whitelist.txt");

    private static volatile P2PAuditLog instance;
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();
    private final Set<String> recentAlerts = ConcurrentHashMap.newKeySet(); // Dedup alerts within session

    public static synchronized P2PAuditLog getInstance() {
        if (instance == null) {
            instance = new P2PAuditLog();
        }
        return instance;
    }

    private P2PAuditLog() {
        loadWhitelist();
    }

    // ==========================================================================
    // Audit Logging
    // ==========================================================================

    public enum EventType {
        CONNECT_INBOUND,
        CONNECT_OUTBOUND,
        DISCONNECT,
        AUTH_SUCCESS,
        AUTH_FAILURE,
        WHITELIST_REJECTED,
        MESSAGE_RECEIVED,
        PEER_HELLO
    }

    /**
     * Log a P2P security event.
     */
    public void log(EventType event, String source, String details) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String entry = String.format("[%s] %-20s | %-15s | %s", timestamp, event, source, details);

        // Write to file
        writeToFile(entry);

        // Console alerts for security-critical events
        if (event == EventType.AUTH_FAILURE) {
            alertAuthFailure(source, details);
        } else if (event == EventType.WHITELIST_REJECTED) {
            alertWhitelistRejection(source);
        }
    }

    private void writeToFile(String entry) {
        try {
            Path parent = AUDIT_LOG_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(AUDIT_LOG_PATH,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(entry);
                writer.newLine();
            }
        } catch (IOException e) {
            // Silent — don't let logging failures break the app
        }
    }

    // ==========================================================================
    // Console Alerts
    // ==========================================================================

    private void alertAuthFailure(String source, String details) {
        String alertKey = "auth_fail:" + source;
        if (recentAlerts.add(alertKey)) { // Only alert once per source per session
            System.out.println();
            System.out.println(ANSI_RED_BOLD + "  ⚠ SECURITY ALERT: Authentication failure!" + ANSI_RESET);
            System.out.println(ANSI_RED_BOLD + "    Source: " + source + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "    Details: " + details + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "    A peer attempted to connect with an invalid signature." + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "    If unexpected, check ~/.mkpro/p2p_audit.log" + ANSI_RESET);
            System.out.println();
        }
    }

    private void alertWhitelistRejection(String source) {
        String alertKey = "whitelist:" + source;
        if (recentAlerts.add(alertKey)) {
            System.out.println();
            System.out.println(ANSI_YELLOW + "  ⚠ Connection rejected: " + source + " (not in whitelist)" + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "    Add to ~/.mkpro/p2p_whitelist.txt to allow." + ANSI_RESET);
            System.out.println();
        }
    }

    // ==========================================================================
    // Connection Whitelist
    // ==========================================================================

    /**
     * Check if a source IP/peer is allowed to connect.
     * Returns true if whitelist is empty (open mode) or source is in the list.
     */
    public boolean isAllowed(String sourceIp) {
        if (whitelist.isEmpty()) {
            return true; // Open mode — no whitelist configured
        }
        // Check exact match and prefix match (e.g., "192.168.1." allows whole subnet)
        for (String entry : whitelist) {
            if (sourceIp.equals(entry) || sourceIp.startsWith(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add an IP or peer ID to the whitelist (persists to file).
     */
    public void addToWhitelist(String entry) {
        whitelist.add(entry.trim());
        saveWhitelist();
    }

    /**
     * Remove from whitelist.
     */
    public void removeFromWhitelist(String entry) {
        whitelist.remove(entry.trim());
        saveWhitelist();
    }

    /**
     * Get current whitelist entries.
     */
    public Set<String> getWhitelist() {
        return Collections.unmodifiableSet(whitelist);
    }

    /**
     * Check if whitelist is active (has entries).
     */
    public boolean isWhitelistActive() {
        return !whitelist.isEmpty();
    }

    private void loadWhitelist() {
        if (Files.exists(WHITELIST_PATH)) {
            try {
                Files.readAllLines(WHITELIST_PATH).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(whitelist::add);
            } catch (IOException e) {
                // Silent
            }
        }
    }

    private void saveWhitelist() {
        try {
            Path parent = WHITELIST_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(WHITELIST_PATH, whitelist);
        } catch (IOException e) {
            System.err.println("Failed to save whitelist: " + e.getMessage());
        }
    }
}
