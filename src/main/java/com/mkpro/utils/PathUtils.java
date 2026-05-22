package com.mkpro.utils;

import java.io.IOException;
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
}
