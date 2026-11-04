package com.mkpro.events;

import com.mkpro.core.MkProContext;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Self-Healing Code-Test Loop / Auto-Verify Gate.
 * Automatically runs a lightweight compiler/syntax check after code edits in a turn
 * and provides structured error feedback for self-healing follow-up turns.
 */
public class AutoVerifyGate {

    public record VerifyResult(
        boolean ran,
        boolean passed,
        String command,
        String summary,
        String errorOutput,
        List<String> checkedFiles
    ) {}

    /**
     * Verifies the modified files using the appropriate compiler or syntax checker.
     */
    public static VerifyResult verifyFiles(MkProContext context, Collection<String> modifiedFiles, boolean forceRun) {
        if (!forceRun && (context == null || !context.isAutoVerify())) {
            return new VerifyResult(false, true, "", "Skipped (auto-verify disabled)", "", List.of());
        }

        List<String> checkedFiles = new ArrayList<>();
        boolean hasJava = false;
        boolean hasPy = false;
        boolean hasGo = false;
        boolean hasJs = false;
        boolean hasTs = false;

        if (modifiedFiles != null) {
            for (String f : modifiedFiles) {
                if (f == null) continue;
                String lower = f.toLowerCase();
                if (lower.endsWith(".java")) {
                    hasJava = true;
                    checkedFiles.add(f);
                } else if (lower.endsWith(".py")) {
                    hasPy = true;
                    checkedFiles.add(f);
                } else if (lower.endsWith(".go")) {
                    hasGo = true;
                    checkedFiles.add(f);
                } else if (lower.endsWith(".js")) {
                    hasJs = true;
                    checkedFiles.add(f);
                } else if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
                    hasTs = true;
                    checkedFiles.add(f);
                }
            }
        }

        if (checkedFiles.isEmpty()) {
            if (!forceRun) {
                return new VerifyResult(false, true, "", "No verifiable source files modified", "", List.of());
            }
            if (Files.exists(Paths.get("mkpro/pom.xml")) || Files.exists(Paths.get("pom.xml"))) {
                hasJava = true;
            } else {
                String msg = "No verifiable source files modified";
                if (context != null) {
                    context.setLastVerifyResult(true, msg);
                }
                return new VerifyResult(false, true, "", msg, "", List.of());
            }
        }

        List<String> cmd = new ArrayList<>();
        File workingDir = new File(".");
        String displayCmd;

        if (hasJava) {
            Path mkproPom = Paths.get("mkpro", "pom.xml");
            Path rootPom = Paths.get("pom.xml");
            if (Files.exists(mkproPom)) {
                workingDir = new File("mkpro");
                cmd.add("mvn");
                cmd.add("test-compile");
                cmd.add("-q");
                displayCmd = "mvn test-compile -q (in mkpro/)";
            } else if (Files.exists(rootPom)) {
                cmd.add("mvn");
                cmd.add("test-compile");
                cmd.add("-q");
                displayCmd = "mvn test-compile -q";
            } else {
                cmd.add("javac");
                cmd.addAll(checkedFiles);
                displayCmd = "javac " + String.join(" ", checkedFiles);
            }
        } else if (hasPy) {
            String pyFile = checkedFiles.stream().filter(f -> f.toLowerCase().endsWith(".py")).findFirst().orElse(checkedFiles.get(0));
            cmd.add("python");
            cmd.add("-m");
            cmd.add("py_compile");
            cmd.add(pyFile);
            displayCmd = "python -m py_compile " + pyFile;
        } else if (hasGo) {
            cmd.add("go");
            cmd.add("build");
            cmd.add("./...");
            displayCmd = "go build ./...";
        } else if (hasJs) {
            String jsFile = checkedFiles.stream().filter(f -> f.toLowerCase().endsWith(".js")).findFirst().orElse(checkedFiles.get(0));
            cmd.add("node");
            cmd.add("--check");
            cmd.add(jsFile);
            displayCmd = "node --check " + jsFile;
        } else if (hasTs) {
            cmd.add("npx");
            cmd.add("tsc");
            cmd.add("--noEmit");
            displayCmd = "npx tsc --noEmit";
        } else {
            return new VerifyResult(false, true, "", "No supported verifier found", "", checkedFiles);
        }

        emitSystemEvent(context, "[Auto-Verify] Running: " + displayCmd);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String summary = "Verification timed out after 60s (" + displayCmd + ")";
                if (context != null) {
                    context.setLastVerifyResult(false, summary);
                }
                emitSystemEvent(context, "[Auto-Verify] FAILED: " + summary);
                return new VerifyResult(true, false, displayCmd, summary, output.toString(), checkedFiles);
            }

            int exitCode = process.exitValue();
            boolean passed = (exitCode == 0);
            String rawOut = output.toString().trim();
            if (rawOut.length() > 4000) {
                rawOut = rawOut.substring(0, 4000) + "\n...[truncated]";
            }

            String summary = passed
                ? "PASSED (" + displayCmd + ")"
                : "FAILED (exit " + exitCode + ": " + displayCmd + ")";

            if (context != null) {
                context.setLastVerifyResult(passed, summary);
            }
            emitSystemEvent(context, "[Auto-Verify] " + summary);

            return new VerifyResult(true, passed, displayCmd, summary, passed ? "" : rawOut, checkedFiles);
        } catch (Exception e) {
            String summary = "Verification error (" + displayCmd + "): " + e.getMessage();
            if (context != null) {
                context.setLastVerifyResult(false, summary);
            }
            emitSystemEvent(context, "[Auto-Verify] " + summary);
            return new VerifyResult(true, false, displayCmd, summary, e.getMessage() != null ? e.getMessage() : "", checkedFiles);
        }
    }

    public static String buildSelfHealPrompt(VerifyResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Auto-Verify Self-Heal] Verification command `").append(result.command()).append("` failed");
        if (!result.checkedFiles().isEmpty()) {
            sb.append(" after modifying ").append(String.join(", ", result.checkedFiles()));
        }
        sb.append(".\nPlease fix the following compilation/syntax errors:\n```\n");
        sb.append(result.errorOutput() != null && !result.errorOutput().isBlank() ? result.errorOutput() : result.summary());
        sb.append("\n```");
        return sb.toString();
    }

    private static void emitSystemEvent(MkProContext context, String message) {
        MkProEvent event = MkProEvent.system(message);
        MkProEventBus bus = MkProEventBus.INSTANCE;
        if (bus != null) {
            bus.emit(event);
        } else if (context != null && context.getEventBus() != null) {
            context.getEventBus().emit(event);
        }
    }
}
