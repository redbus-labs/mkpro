// Academic UI Verified
package com.mkpro.utils;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Utility class for managing OS-standard application paths and file system operations.
 */
public class PathUtils {

    private static final String APP_DIR = "mkpro";
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    // ==========================================
    // OS Detection Methods
    // ==========================================

    public static boolean isWindows() {
        return OS_NAME.contains("win");
    }

    public static boolean isMac() {
        return OS_NAME.contains("mac");
    }

    public static boolean isLinux() {
        return OS_NAME.contains("nux") || OS_NAME.contains("nix") || OS_NAME.contains("aix");
    }

    public static String getUserHome() {
        return System.getProperty("user.home", ".");
    }

    // ==========================================
    // OS-Standard Application Paths
    // ==========================================

    /**
     * Configuration directory:
     * - Windows: %APPDATA%\mkpro (fallback: ~/.AppData/Roaming/mkpro)
     * - Linux/macOS: $XDG_CONFIG_HOME/mkpro (fallback: ~/.config/mkpro)
     */
    public static Path getConfigDir() {
        return getConfigDir(true);
    }

    public static Path getConfigDir(boolean create) {
        Path dir;
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            dir = (appData != null && !appData.isBlank())
                    ? Paths.get(appData, APP_DIR)
                    : Paths.get(getUserHome(), "AppData", "Roaming", APP_DIR);
        } else {
            String xdgConfig = System.getenv("XDG_CONFIG_HOME");
            dir = (xdgConfig != null && !xdgConfig.isBlank())
                    ? Paths.get(xdgConfig, APP_DIR)
                    : Paths.get(getUserHome(), ".config", APP_DIR);
        }
        return create ? ensureDirectory(dir) : dir;
    }

    /**
     * Data directory:
     * - Windows: %LOCALAPPDATA%\mkpro\data (fallback: ~/.AppData/Local/mkpro/data)
     * - Linux/macOS: $XDG_DATA_HOME/mkpro (fallback: ~/.local/share/mkpro)
     */
    public static Path getDataDir() {
        return getDataDir(true);
    }

    public static Path getDataDir(boolean create) {
        Path dir;
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            dir = (localAppData != null && !localAppData.isBlank())
                    ? Paths.get(localAppData, APP_DIR, "data")
                    : Paths.get(getUserHome(), "AppData", "Local", APP_DIR, "data");
        } else {
            String xdgData = System.getenv("XDG_DATA_HOME");
            dir = (xdgData != null && !xdgData.isBlank())
                    ? Paths.get(xdgData, APP_DIR)
                    : Paths.get(getUserHome(), ".local", "share", APP_DIR);
        }
        return create ? ensureDirectory(dir) : dir;
    }

    /**
     * Cache directory:
     * - Windows: %LOCALAPPDATA%\mkpro\cache (fallback: ~/.AppData/Local/mkpro/cache)
     * - Linux/macOS: $XDG_CACHE_HOME/mkpro (fallback: ~/.cache/mkpro)
     */
    public static Path getCacheDir() {
        return getCacheDir(true);
    }

    public static Path getCacheDir(boolean create) {
        Path dir;
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            dir = (localAppData != null && !localAppData.isBlank())
                    ? Paths.get(localAppData, APP_DIR, "cache")
                    : Paths.get(getUserHome(), "AppData", "Local", APP_DIR, "cache");
        } else {
            String xdgCache = System.getenv("XDG_CACHE_HOME");
            dir = (xdgCache != null && !xdgCache.isBlank())
                    ? Paths.get(xdgCache, APP_DIR)
                    : Paths.get(getUserHome(), ".cache", APP_DIR);
        }
        return create ? ensureDirectory(dir) : dir;
    }

    /**
     * Log directory:
     * - Windows: %LOCALAPPDATA%\mkpro\logs (fallback: ~/.AppData/Local/mkpro/logs)
     * - Linux/macOS: $XDG_STATE_HOME/mkpro/logs (fallback: ~/.local/state/mkpro/logs)
     */
    public static Path getLogDir() {
        return getLogDir(true);
    }

    public static Path getLogDir(boolean create) {
        Path dir;
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            dir = (localAppData != null && !localAppData.isBlank())
                    ? Paths.get(localAppData, APP_DIR, "logs")
                    : Paths.get(getUserHome(), "AppData", "Local", APP_DIR, "logs");
        } else {
            String xdgState = System.getenv("XDG_STATE_HOME");
            dir = (xdgState != null && !xdgState.isBlank())
                    ? Paths.get(xdgState, APP_DIR, "logs")
                    : Paths.get(getUserHome(), ".local", "state", APP_DIR, "logs");
        }
        return create ? ensureDirectory(dir) : dir;
    }

    /**
     * Models directory (under data directory).
     */
    public static Path getModelsDir() {
        return getModelsDir(true);
    }

    public static Path getModelsDir(boolean create) {
        Path dir = getDataDir(false).resolve("models");
        return create ? ensureDirectory(dir) : dir;
    }

    /**
     * Backups directory (under data directory).
     */
    public static Path getBackupsDir() {
        return getBackupsDir(true);
    }

    public static Path getBackupsDir(boolean create) {
        Path dir = getDataDir(false).resolve("backups");
        return create ? ensureDirectory(dir) : dir;
    }

    /**
     * Legacy Documents folder location (~/Documents/mkpro) for backward-compatible migration checks.
     */
    public static Path getLegacyDocumentsPath() {
        return Paths.get(getUserHome(), "Documents", APP_DIR);
    }

    /**
     * Backward-compatible alias for existing callers pointing to the primary data directory.
     */
    public static Path getBaseDocumentsPath() {
        return getDataDir();
    }

    public static Path getProjectPath() {
        return Paths.get(System.getProperty("user.dir", "."));
    }

    /**
     * Get the .mkpro data directory for the current project.
     * All runtime-generated data (training, exports, schedules) goes here.
     */
    public static Path getMkproDataDir() {
        return getProjectPath().resolve(".mkpro");
    }

    /**
     * Ensures that the specified directory exists, creating all parent directories if necessary.
     */
    public static Path ensureDirectory(Path dir) {
        if (dir != null) {
            try {
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                }
            } catch (IOException ignored) {
            }
        }
        return dir;
    }

    public static void ensureDirectoriesExist(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Finds an available port starting from the given port.
     * @param startPort The port to start searching from.
     * @return An available port.
     */
    public static int findAvailablePort(int startPort) {
        int port = startPort;
        while (port < 65535) {
            try (ServerSocket ignored = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                port++;
            }
        }
        return -1;
    }
}