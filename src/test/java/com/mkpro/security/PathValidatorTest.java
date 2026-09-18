package com.mkpro.security;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PathValidatorTest {

    private static Path testProjectRoot;
    private static Path testSubDir;
    private PathValidator validator;

    @BeforeAll
    static void createTestStructure() throws IOException {
        testProjectRoot = Files.createTempDirectory("pvtest-project-");
        testSubDir = Files.createDirectories(testProjectRoot.resolve("src").resolve("main"));
        Files.writeString(testProjectRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(testSubDir.resolve("App.java"), "class App {}");
    }

    @AfterAll
    static void cleanupTestStructure() {
        try {
            Files.walk(testProjectRoot)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void setUp() {
        validator = PathValidator.initialize(testProjectRoot, null);
    }

    // --- Allowed paths ---

    @Test
    void allowsFileWithinProjectRoot() {
        Path result = validator.validate(testProjectRoot.resolve("pom.xml").toString());
        assertNotNull(result);
    }

    @Test
    void allowsFileInSubdirectory() {
        Path result = validator.validate(testSubDir.resolve("App.java").toString());
        assertNotNull(result);
    }

    @Test
    void allowsTempDirectory() {
        String tempDir = System.getProperty("java.io.tmpdir");
        Path result = validator.validate(tempDir);
        assertNotNull(result);
    }

    @Test
    void allowsAdditionalSafePaths() throws IOException {
        Path extraSafe = Files.createTempDirectory("pvtest-extra-");
        try {
            PathValidator withExtra = PathValidator.initialize(testProjectRoot, List.of(extraSafe));
            Path result = withExtra.validate(extraSafe.toString());
            assertNotNull(result);
        } finally {
            Files.deleteIfExists(extraSafe);
        }
    }

    @Test
    void allowsNewFileInExistingDir() {
        String newFile = testSubDir.resolve("NewClass.java").toString();
        Path result = validator.validate(newFile);
        assertNotNull(result);
    }

    // --- Path traversal attacks ---

    @Test
    void blocksTraversalAboveRoot() {
        String traversal = testProjectRoot.resolve("../../etc/passwd").toString();
        assertThrows(SecurityException.class, () -> validator.validate(traversal));
    }

    @Test
    void blocksTraversalWithMultipleDotDot() {
        String traversal = testProjectRoot.resolve("src/../../..").toString();
        assertThrows(SecurityException.class, () -> validator.validate(traversal));
    }

    @Test
    void blocksAbsolutePathOutsideRoot() {
        String systemPath = System.getProperty("os.name").toLowerCase().contains("win")
            ? "C:\\Windows\\System32\\cmd.exe"
            : "/etc/passwd";
        assertThrows(SecurityException.class, () -> validator.validate(systemPath));
    }

    @Test
    void blocksAccessToUserHome() {
        String homePath = System.getProperty("user.home");
        if (!Path.of(homePath).startsWith(testProjectRoot)) {
            assertThrows(SecurityException.class, () -> validator.validate(homePath));
        }
    }

    // --- Blocked sensitive patterns ---

    @Test
    void blocksEnvFile() throws IOException {
        Path envFile = testProjectRoot.resolve(".env");
        Files.writeString(envFile, "SECRET=value");
        try {
            assertThrows(SecurityException.class, () -> validator.validate(envFile.toString()));
        } finally {
            Files.deleteIfExists(envFile);
        }
    }

    @Test
    void blocksSshPrivateKey() {
        assertThrows(SecurityException.class,
            () -> validator.validate(testProjectRoot.resolve("id_rsa").toString()));
    }

    @Test
    void blocksEd25519Key() {
        assertThrows(SecurityException.class,
            () -> validator.validate(testProjectRoot.resolve("id_ed25519").toString()));
    }

    @Test
    void blocksPemFile() {
        assertThrows(SecurityException.class,
            () -> validator.validate(testProjectRoot.resolve("server.pem").toString()));
    }

    @Test
    void blocksCredentialsJson() {
        assertThrows(SecurityException.class,
            () -> validator.validate(testProjectRoot.resolve("credentials.json").toString()));
    }

    @Test
    void blocksAwsCredentials() {
        assertThrows(SecurityException.class,
            () -> validator.validate(testProjectRoot.resolve(".aws/credentials").toString()));
    }

    @Test
    void blocksSshDirectory() {
        assertThrows(SecurityException.class,
            () -> validator.validate(testProjectRoot.resolve(".ssh/config").toString()));
    }

    // --- Edge cases ---

    @Test
    void nullPathThrows() {
        assertThrows(SecurityException.class, () -> validator.validate(null));
    }

    @Test
    void emptyPathThrows() {
        assertThrows(SecurityException.class, () -> validator.validate(""));
    }

    @Test
    void blankPathThrows() {
        assertThrows(SecurityException.class, () -> validator.validate("   "));
    }

    @Test
    void isAllowedReturnsFalseForBlocked() {
        assertFalse(validator.isAllowed("/etc/shadow"));
    }

    @Test
    void isAllowedReturnsTrueForValid() {
        assertTrue(validator.isAllowed(testProjectRoot.resolve("pom.xml").toString()));
    }

    // --- validateForRead ---

    @Test
    void validateForReadAllowsMkproConfigDir() {
        Path mkproConfig = Path.of(System.getProperty("user.home"), ".mkpro");
        if (Files.exists(mkproConfig)) {
            Path result = validator.validateForRead(mkproConfig.toString());
            assertNotNull(result);
        }
    }

    @Test
    void validateForReadBlocksSensitiveInMkpro() {
        String sensitiveInMkpro = Path.of(System.getProperty("user.home"), ".mkpro", "id_rsa").toString();
        assertThrows(SecurityException.class, () -> validator.validateForRead(sensitiveInMkpro));
    }

    // --- Metadata ---

    @Test
    void allowedRootsIncludesTempDir() throws IOException {
        List<Path> roots = validator.getAllowedRoots();
        Path tempPath = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        assertTrue(roots.stream().anyMatch(r -> {
            try { return r.toRealPath().equals(tempPath); }
            catch (Exception e) { return r.equals(tempPath); }
        }), "Allowed roots should include temp dir. Roots: " + roots + " | expected: " + tempPath);
    }

    @Test
    void getInstanceNeverReturnsNull() {
        assertNotNull(PathValidator.getInstance());
    }
}
