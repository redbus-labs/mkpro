package com.mkpro.utils;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathUtils {

    private static final String APP_DIR = "mkpro";

    public static Path getBaseDocumentsPath() {
        String userHome = System.getProperty("user.home");
        // Always target the standard local Documents folder to prevent OneDrive sync lock and corruption issues
        Path documents = Paths.get(userHome, "Documents");
        return documents.resolve(APP_DIR);
    }

    public static Path getProjectPath() {
        return Paths.get(System.getProperty("user.dir"));
    }

    public static void ensureDirectoriesExist(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Resolves a writable directory for MapDB data files.
     * Prefers {@code <project>/.mkpro}, then falls back to {@code ~/Documents/mkpro}.
     */
    public static Path resolveMkproDataDir(Path projectPath) throws IOException {
        Path projectMkpro = projectPath.resolve(".mkpro");
        if (isDirectoryWritable(projectMkpro)) {
            return projectMkpro;
        }

        Path documentsMkpro = getBaseDocumentsPath();
        if (isDirectoryWritable(documentsMkpro)) {
            System.err.println(
                "\u001b[33m[Warning] Project .mkpro directory is not writable (check file ownership/permissions). "
                    + "Using " + documentsMkpro + " for storage instead.\u001b[0m"
            );
            return documentsMkpro;
        }

        throw new IOException(
            "No writable mkpro data directory found. Fix permissions on "
                + projectMkpro + " or " + documentsMkpro
        );
    }

    public static boolean isDirectoryWritable(Path dir) {
        try {
            ensureDirectoriesExist(dir.resolve("dummy"));
            Path probe = dir.resolve(".write_probe_" + System.nanoTime());
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
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
