package com.mkpro.graph;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.stream.Stream;

/**
 * Utility to fetch the current Git commit hash of a workspace,
 * with a bulletproof SHA-256 fallback for non-Git codebases.
 */
public class GitUtil {

    public static String getCommitHashOrFallback(Path projectDir) {
        // 1. Try executing the Git CLI
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"git", "rev-parse", "HEAD"}, null, projectDir.toFile());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null && line.trim().length() == 40) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {
        }

        // 2. Try reading .git/HEAD directly from disk
        try {
            Path gitHead = projectDir.resolve(".git/HEAD");
            if (Files.exists(gitHead)) {
                String headContent = Files.readString(gitHead, StandardCharsets.UTF_8).trim();
                if (headContent.startsWith("ref:")) {
                    String refPath = headContent.substring(4).trim();
                    Path refFile = projectDir.resolve(".git").resolve(refPath);
                    if (Files.exists(refFile)) {
                        return Files.readString(refFile, StandardCharsets.UTF_8).trim();
                    }
                } else if (headContent.length() == 40) {
                    return headContent;
                }
            }
        } catch (Exception ignored) {
        }

        // 3. Fallback: Generate a deterministic hash of the files in the workspace (SHA-256)
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> paths = Files.walk(projectDir)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> !p.toString().contains(".git") 
                               && !p.toString().contains("build") 
                               && !p.toString().contains(".gradle")
                               && !p.toString().contains(".graphify"))
                     .sorted()
                     .forEach(p -> {
                         try {
                             File f = p.toFile();
                             digest.update(p.toString().getBytes(StandardCharsets.UTF_8));
                             digest.update(String.valueOf(f.length()).getBytes(StandardCharsets.UTF_8));
                             digest.update(String.valueOf(f.lastModified()).getBytes(StandardCharsets.UTF_8));
                         } catch (Exception ignored) {
                         }
                     });
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16) + "-fallback"; // Unique 16-char code-state hash
        } catch (Exception e) {
            return "static-fallback-version";
        }
    }
}
