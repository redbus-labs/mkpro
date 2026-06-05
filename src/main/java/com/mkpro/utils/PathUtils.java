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
