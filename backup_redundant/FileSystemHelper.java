package com.mkpro.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Utility class for file system operations, specifically focusing on security and permissions.
 */
public class FileSystemHelper {

    /**
     * Creates a directory with restricted permissions (readable/writable only by the owner).
     * On POSIX systems, this sets 700 (rwx------) permissions.
     * On Windows, it uses the standard File.setReadable/setWritable methods.
     *
     * @param path The path of the directory to create.
     * @return The created directory Path.
     * @throws IOException If directory creation or permission setting fails.
     */
    public static Path createRestrictedDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }

        try {
            // Try POSIX permissions (Linux/macOS)
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {
            // Fallback for Windows or non-POSIX file systems
            path.toFile().setReadable(false, false); // Deny all
            path.toFile().setWritable(false, false); // Deny all
            path.toFile().setExecutable(false, false); // Deny all
            
            path.toFile().setReadable(true, true);  // Allow owner
            path.toFile().setWritable(true, true);  // Allow owner
            path.toFile().setExecutable(true, true); // Allow owner
        }

        return path;
    }
}
