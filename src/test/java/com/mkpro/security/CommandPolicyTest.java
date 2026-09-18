package com.mkpro.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

public class CommandPolicyTest {

    private static CommandPolicy policy;

    @BeforeAll
    static void setUp() {
        policy = CommandPolicy.getInstance();
    }

    // --- Allowlist passes ---

    @ParameterizedTest
    @ValueSource(strings = {
        "git status", "git add .", "git commit -m 'test'", "git push origin feature-branch",
        "mvn clean install", "gradle build", "npm install", "npm run test",
        "node server.js", "python script.py", "python3 -m pytest", "pip install requests",
        "java -jar app.jar", "javac Main.java", "cargo build --release", "go test ./...",
        "docker build -t myapp .", "kubectl get pods", "aws s3 ls",
        "ls -la", "cat file.txt", "grep -r 'pattern' src/", "curl https://api.example.com",
        "echo hello", "mkdir new-dir", "cp file1.txt file2.txt", "whoami", "ping localhost"
    })
    void allowedCommandsPass(String command) {
        CommandPolicy.PolicyResult result = policy.evaluate(command);
        assertTrue(result.isAllowed(), "Expected allowed: " + command + " | reason: " + result.getReason());
    }

    // --- Blocked commands (not in allowlist) ---

    @ParameterizedTest
    @ValueSource(strings = {
        "rm -rf /", "shutdown now", "reboot", "poweroff", "halt",
        "dd if=/dev/zero of=/dev/sda", "nc -e /bin/bash", "telnet",
        "nmap localhost", "apt-get install malware", "systemctl stop firewall"
    })
    void blockedCommandsDenied(String command) {
        CommandPolicy.PolicyResult result = policy.evaluate(command);
        assertFalse(result.isAllowed(), "Expected blocked: " + command);
    }

    // --- Dangerous patterns on allowed commands ---

    @Test
    void blocksForcePushToMain() {
        assertFalse(policy.evaluate("git push --force origin main").isAllowed());
    }

    @Test
    void blocksForcePushToMaster() {
        assertFalse(policy.evaluate("git push --force origin master").isAllowed());
    }

    @Test
    void blocksDeleteMainBranch() {
        assertFalse(policy.evaluate("git branch -D main").isAllowed());
    }

    @Test
    void allowsRegularGitPush() {
        assertTrue(policy.evaluate("git push origin feature-branch").isAllowed());
    }

    @Test
    void allowsDeleteFeatureBranch() {
        assertTrue(policy.evaluate("git branch -D feature-x").isAllowed());
    }

    // --- Chained commands ---

    @Test
    void allowsChainedAllowedCommands() {
        assertTrue(policy.evaluateChained("git status; mvn test").isAllowed());
    }

    @Test
    void blocksChainWithDangerousCommand() {
        assertFalse(policy.evaluateChained("git status; rm -rf /").isAllowed());
    }

    @Test
    void blocksChainViaPipe() {
        assertFalse(policy.evaluateChained("ls | xargs rm").isAllowed());
    }

    @Test
    void blocksChainViaAmpersand() {
        assertFalse(policy.evaluateChained("echo hello && shutdown -h now").isAllowed());
    }

    // --- Edge cases ---

    @Test
    void nullCommandDenied() {
        assertFalse(policy.evaluate(null).isAllowed());
    }

    @Test
    void emptyCommandDenied() {
        assertFalse(policy.evaluate("").isAllowed());
    }

    @Test
    void blankCommandDenied() {
        assertFalse(policy.evaluate("   ").isAllowed());
    }

    @Test
    void nullChainedCommandDenied() {
        assertFalse(policy.evaluateChained(null).isAllowed());
    }

    @Test
    void commandExceedingMaxLengthDenied() {
        String longCommand = "git " + "a".repeat(5000);
        CommandPolicy.PolicyResult result = policy.evaluate(longCommand);
        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("maximum length"));
    }

    @Test
    void fullPathCommandAllowed() {
        assertTrue(policy.evaluate("/usr/bin/git status").isAllowed());
    }

    @Test
    void windowsPathCommandAllowed() {
        assertTrue(policy.evaluate("C:\\Git\\bin\\git.exe status").isAllowed());
    }

    @Test
    void sudoPrefixStripped() {
        assertTrue(policy.evaluate("sudo git status").isAllowed());
    }

    @Test
    void sudoWithDangerousBlocked() {
        assertFalse(policy.evaluate("sudo rm -rf /").isAllowed());
    }

    @Test
    void cmdWrappedAllowed() {
        assertTrue(policy.evaluate("cmd /c git status").isAllowed());
    }

    @Test
    void denyReasonIsPopulated() {
        CommandPolicy.PolicyResult result = policy.evaluate("malicious_binary");
        assertFalse(result.isAllowed());
        assertNotNull(result.getReason());
        assertFalse(result.getReason().isEmpty());
    }

    @Test
    void allowReasonIsNull() {
        CommandPolicy.PolicyResult result = policy.evaluate("git status");
        assertTrue(result.isAllowed());
        assertNull(result.getReason());
    }

    @Test
    void defaultPolicyHasExpectedCommands() {
        var allowed = policy.getAllowedCommands();
        assertTrue(allowed.contains("git"));
        assertTrue(allowed.contains("mvn"));
        assertTrue(allowed.contains("npm"));
        assertTrue(allowed.contains("docker"));
        assertTrue(allowed.contains("python"));
        assertTrue(allowed.contains("curl"));
    }
}
