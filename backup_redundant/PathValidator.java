package com.mkpro.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PathValidator restricts file operations to configured safe directories.
 * Prevents path traversal attacks, symlink escapes, and access to sensitive system paths.
 */
public class PathValidator {

    private final List<Path> allowedRoots;
    private final List<String> blockedPatterns;

    private static volatile PathValidator instance;

    private PathValidator(List<Path> allowedRoots) {
        this.allowedRoots = Collections.unmodifiableList(allowedRoots);
        List<String> patterns = new ArrayList<>(List.of(
            ".env",
            "id_rsa",
            "id_ed25519",
            ".pem",
            "credentials.json",
            ".aws/credentials",
            ".ssh/",
            "shadow",
            "passwd",
            "certs/" // Added per mTLS rotation requirements
        ));
        this.blockedPatterns = Collections.unmodifiableList(patterns);
    }

    /**
     * Initialize the singleton with the project root and optional additional safe paths.
     */
    public static synchronized PathValidator initialize(Path projectRoot, List<Path> additionalSafePaths) {
        List<Path> roots = new ArrayList<>();
        roots.add(normalize(projectRoot));
        if (additionalSafePaths != null) {
            for (Path p : additionalSafePaths) {
                roots.add(normalize(p));
            }
        }
        // Always allow the temp directory for tool outputs
        roots.add(normalize(Paths.get(System.getProperty("java.io.tmpdir"))));
        instance = new PathValidator(roots);
        return instance;
    }

    /**
     * Get the singleton instance. Falls back to a restrictive validator if not initialized.
     */
    public static PathValidator getInstance() {
        if (instance == null) {
            synchronized (PathValidator.class) {
                if (instance == null) {
                    // Fallback: allow only current working directory + temp
                    initialize(Paths.get(System.getProperty("user.dir")), null);
                }
            }
        }
        return instance;
    }

    /**
     * Validates that the given path is within allowed roots and not a sensitive file.
     *
     * @param pathStr The raw path string from the tool invocation
     * @return The validated, canonicalized Path
     * @throws SecurityException if the path is outside allowed roots or is a sensitive file
     */
    public Path validate(String pathStr) throws SecurityException {
        if (pathStr == null || pathStr.isBlank()) {
            throw new SecurityException("Path cannot be null or empty");
        }

        Path requested;
        try {
            requested = Paths.get(pathStr).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new SecurityException("Invalid path: " + pathStr);
        }

        // Resolve symlinks to prevent escape via symlink chains
        Path resolved;
        try {
            if (Files.exists(requested)) {
                resolved = requested.toRealPath();
            } else {
                // For new files, resolve the parent to verify it exists within bounds
                Path parent = requested.getParent();
                if (parent != null && Files.exists(parent)) {
                    resolved = parent.toRealPath().resolve(requested.getFileName());
                } else {
                    resolved = requested;
                }
            }
        } catch (IOException e) {
            throw new SecurityException("Cannot resolve path: " + pathStr + " (" + e.getMessage() + ")");
        }

        // Check if path is within any allowed root
        boolean withinAllowed = false;
        for (Path root : allowedRoots) {
            if (resolved.startsWith(root)) {
                withinAllowed = true;
                break;
            }
        }

        if (!withinAllowed) {
            throw new SecurityException(
                "Access denied: path '" + pathStr + "' is outside allowed directories. " +
                "Allowed roots: " + allowedRoots
            );
        }

        // Check for sensitive file patterns
        String pathLower = resolved.toString().toLowerCase().replace('\\', '/');
        
        // mTLS Protection: Block .key and .p12 files unless accessed by CertManager
        if ((pathLower.endsWith(".key") || pathLower.endsWith(".p12")) && !isCalledByCertManager()) {
            throw new SecurityException(
                "Access denied: access to certificate keys/stores is restricted to CertManager only."
            );
        }

        for (String pattern : blockedPatterns) {
            if (pathLower.contains(pattern.toLowerCase())) {
                // Special exception for CertManager accessing the certs/ directory
                if (pattern.equals("certs/") && isCalledByCertManager()) {
                    continue;
                }
                throw new SecurityException(
                    "Access denied: path '" + pathStr + "' matches blocked sensitive pattern '" + pattern + "'"
                );
            }
        }

        return resolved;
    }

    /**
     * Validates a path for read-only access (slightly more permissive — allows reading
     * from the user's home .mkpro directory for configuration).
     */
    public Path validateForRead(String pathStr) throws SecurityException {
        try {
            return validate(pathStr);
        } catch (SecurityException e) {
            // Additional check: allow reading from ~/.mkpro/ for config purposes
            Path requested = Paths.get(pathStr).toAbsolutePath().normalize();
            Path mkproHome = Paths.get(System.getProperty("user.home"), ".mkpro");
            
            if (requested.startsWith(mkproHome)) {
                String pathLower = requested.toString().toLowerCase().replace('\\', '/');
                
                // Still enforce .key/.p12 restriction even in home directory
                if ((pathLower.endsWith(".key") || pathLower.endsWith(".p12")) && !isCalledByCertManager()) {
                    throw e;
                }

                for (String pattern : blockedPatterns) {
                    if (pathLower.contains(pattern.toLowerCase())) {
                        // Allow CertManager to access certs/ in home dir
                        if (pattern.equals("certs/") && isCalledByCertManager()) {
                            continue;
                        }
                        throw e; // Re-throw original
                    }
                }
                return requested;
            }
            throw e;
        }
    }

    /**
     * Identifies if the current call stack originates from the CertManager.
     */
    private boolean isCalledByCertManager() {
        return StackWalker.getInstance().walk(s -> 
            s.anyMatch(frame -> frame.getClassName().contains("CertManager"))
        );
    }

    /**
     * Quick check without exception — useful for filtering in directory listings.
     */
    public boolean isAllowed(String pathStr) {
        try {
            validate(pathStr);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    public List<Path> getAllowedRoots() {
        return allowedRoots;
    }

    private static Path normalize(Path path) {
        try {
            // Use toRealPath to resolve symlinks AND Windows 8.3 short names
            if (java.nio.file.Files.exists(path)) {
                return path.toRealPath();
            }
            return path.toAbsolutePath().normalize();
        } catch (Exception e) {
            try {
                return path.toAbsolutePath().normalize();
            } catch (Exception e2) {
                return path.normalize();
            }
        }
    }
}
