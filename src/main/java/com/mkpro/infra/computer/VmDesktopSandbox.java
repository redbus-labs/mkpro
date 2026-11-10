package com.mkpro.infra.computer;

import com.google.genai.types.Blob;
import com.google.genai.types.Part;
import com.mkpro.agents.AgentManager;
import com.mkpro.events.MkProEvent;
import com.mkpro.events.MkProEventBus;
import com.mkpro.infra.ssh.SshSessionManager;
import com.mkpro.utils.PathUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.List;

/**
 * VmDesktopSandbox manages VM-First and Local Fallback Computer Use execution.
 *
 * Modes:
 * - "vm" (default when SSH session is active): Executes X11 actions on remote Ubuntu Xvfb (:99, 1280x800)
 *   using xdotool, xclip, and scrot via SshSessionManager.
 * - "local": Uses java.awt.Robot on the local desktop with a physical 4-corner emergency failsafe.
 */
public class VmDesktopSandbox {

    private static final VmDesktopSandbox INSTANCE = new VmDesktopSandbox();

    public static VmDesktopSandbox getInstance() {
        return INSTANCE;
    }

    public static final String DEFAULT_DISPLAY = ":99";
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 800;
    private static final int FAILSAFE_CORNER_MARGIN_PX = 8;

    private volatile String mode = "vm"; // "vm" or "local"
    private volatile String display = DEFAULT_DISPLAY;
    private volatile int screenWidth = DEFAULT_WIDTH;
    private volatile int screenHeight = DEFAULT_HEIGHT;
    private volatile String lastScreenshotPath = "";
    private volatile String lastFrameBase64 = "";
    private volatile String lastAction = "idle";
    private volatile long lastUpdatedEpochMs = 0L;

    private VmDesktopSandbox() {}

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        if (mode != null && ("local".equalsIgnoreCase(mode.trim()) || "vm".equalsIgnoreCase(mode.trim()))) {
            this.mode = mode.trim().toLowerCase(Locale.ROOT);
        }
    }

    public String getDisplay() {
        return display;
    }

    public void setDisplay(String display) {
        if (display != null && !display.isBlank()) {
            this.display = display.trim();
        }
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public String getLastScreenshotPath() {
        return lastScreenshotPath;
    }

    public String getLastFrameBase64() {
        return lastFrameBase64;
    }

    public String getLastAction() {
        return lastAction;
    }

    public long getLastUpdatedEpochMs() {
        return lastUpdatedEpochMs;
    }

    public synchronized void setLastFrame(String base64, String screenshotPath, String action) {
        if (base64 != null) this.lastFrameBase64 = base64;
        if (screenshotPath != null) this.lastScreenshotPath = screenshotPath;
        if (action != null) this.lastAction = action;
        this.lastUpdatedEpochMs = System.currentTimeMillis();
    }

    // =========================================================================
    // Shell Command Builders (Pure static helpers for deterministic unit testing)
    // =========================================================================

    public static String sanitizeDisplay(String display) {
        if (display == null || display.isBlank()) return DEFAULT_DISPLAY;
        String clean = display.trim();
        if (!clean.matches("^:[0-9]+(\\.[0-9]+)?$")) {
            return DEFAULT_DISPLAY;
        }
        return clean;
    }

    public static String buildClickCommand(String display, int px, int py, int button, boolean doubleClick) {
        String disp = sanitizeDisplay(display);
        int btn = (button >= 1 && button <= 3) ? button : 1;
        String clickPart = doubleClick
                ? ("click --repeat 2 --delay 80 " + btn)
                : ("click " + btn);
        return String.format("DISPLAY=%s xdotool mousemove --sync %d %d %s", disp, px, py, clickPart);
    }

    public static String buildMoveOrDragCommand(String display, int fromPx, int fromPy, int toPx, int toPy, boolean drag) {
        String disp = sanitizeDisplay(display);
        if (!drag) {
            return String.format("DISPLAY=%s xdotool mousemove --sync %d %d", disp, toPx, toPy);
        }
        return String.format(
                "DISPLAY=%s xdotool mousemove --sync %d %d mousedown 1 sleep 0.08 mousemove --sync %d %d sleep 0.08 mouseup 1",
                disp, fromPx, fromPy, toPx, toPy
        );
    }

    public static String buildTypeCommand(String display, String text, boolean pressEnter) {
        String disp = sanitizeDisplay(display);
        String safeText = text != null ? text : "";
        String b64 = Base64.getEncoder().encodeToString(safeText.getBytes(StandardCharsets.UTF_8));
        String enterSuffix = pressEnter ? " && DISPLAY=" + disp + " xdotool key Return" : "";
        return String.format(
                "(printf '%%s' '%s' | base64 -d | DISPLAY=%s xclip -selection clipboard && DISPLAY=%s xdotool key --clearmodifiers ctrl+v)" +
                " || (DISPLAY=%s xdotool type --clearmodifiers --delay 12 -- \"$(printf '%%s' '%s' | base64 -d)\")%s",
                b64, disp, disp, disp, b64, enterSuffix
        );
    }

    public static String buildKeyCommand(String display, String keyCombo) {
        String disp = sanitizeDisplay(display);
        String normalized = normalizeXdotoolKeyCombo(keyCombo);
        return String.format("DISPLAY=%s xdotool key --clearmodifiers %s", disp, shellSingleQuote(normalized));
    }

    public static String buildScrollCommand(String display, int px, int py, String direction, int clicks) {
        String disp = sanitizeDisplay(display);
        int count = Math.max(1, Math.min(25, clicks));
        int btn = 5; // default down
        if (direction != null) {
            String d = direction.trim().toLowerCase(Locale.ROOT);
            if ("up".equals(d)) btn = 4;
            else if ("down".equals(d)) btn = 5;
            else if ("left".equals(d)) btn = 6;
            else if ("right".equals(d)) btn = 7;
        }
        return String.format("DISPLAY=%s xdotool mousemove --sync %d %d click --repeat %d --delay 40 %d",
                disp, px, py, count, btn);
    }

    private static String normalizeXdotoolKeyCombo(String raw) {
        if (raw == null || raw.isBlank()) return "Return";
        String[] parts = raw.trim().split("\\+");
        List<String> mapped = new ArrayList<>();
        for (String p : parts) {
            String k = p.trim();
            if (k.isEmpty()) continue;
            String lower = k.toLowerCase(Locale.ROOT);
            switch (lower) {
                case "enter":
                case "return":
                    mapped.add("Return");
                    break;
                case "esc":
                case "escape":
                    mapped.add("Escape");
                    break;
                case "tab":
                    mapped.add("Tab");
                    break;
                case "space":
                    mapped.add("space");
                    break;
                case "backspace":
                    mapped.add("BackSpace");
                    break;
                case "delete":
                case "del":
                    mapped.add("Delete");
                    break;
                case "up":
                    mapped.add("Up");
                    break;
                case "down":
                    mapped.add("Down");
                    break;
                case "left":
                    mapped.add("Left");
                    break;
                case "right":
                    mapped.add("Right");
                    break;
                case "home":
                    mapped.add("Home");
                    break;
                case "end":
                    mapped.add("End");
                    break;
                case "pageup":
                case "pgup":
                    mapped.add("Prior");
                    break;
                case "pagedown":
                case "pgdn":
                    mapped.add("Next");
                    break;
                case "ctrl":
                case "control":
                    mapped.add("ctrl");
                    break;
                case "alt":
                    mapped.add("alt");
                    break;
                case "shift":
                    mapped.add("shift");
                    break;
                case "super":
                case "win":
                case "meta":
                case "cmd":
                    mapped.add("super");
                    break;
                default:
                    mapped.add(k.replaceAll("[^a-zA-Z0-9_]", ""));
                    break;
            }
        }
        return mapped.isEmpty() ? "Return" : String.join("+", mapped);
    }

    private static String shellSingleQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    // =========================================================================
    // Execution Methods
    // =========================================================================

    private String resolveAlias(String alias) {
        if (alias != null && !alias.isBlank()) {
            return alias.trim();
        }
        SshSessionManager ssh = SshSessionManager.getInstance();
        if (ssh.hasActiveSession("default")) {
            return "default";
        }
        for (SshSessionManager.SessionInfo info : ssh.listSessions()) {
            if (info.isConnected()) {
                return info.getAlias();
            }
        }
        return "default";
    }

    private boolean isVmConnected(String alias) {
        if ("local".equalsIgnoreCase(this.mode)) {
            return false;
        }
        return SshSessionManager.getInstance().hasActiveSession(resolveAlias(alias));
    }

    /**
     * Checks/installs xvfb, openbox, xdotool, scrot, xclip on the remote Ubuntu VM
     * and starts Xvfb :99 (1280x800x24) + openbox if not already running.
     */
    public Map<String, Object> initVmDesktop(String alias) {
        String resolved = resolveAlias(alias);
        SshSessionManager ssh = SshSessionManager.getInstance();
        if (!ssh.hasActiveSession(resolved)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("error", "No active SSH session for alias '" + resolved + "'. Connect via /ssh connect or ssh_connect first, or switch to /computer mode local.");
            return err;
        }

        try {
            String disp = sanitizeDisplay(this.display);
            String bootstrapScript =
                    "export DEBIAN_FRONTEND=noninteractive; " +
                    "if ! command -v Xvfb >/dev/null 2>&1 || ! command -v xdotool >/dev/null 2>&1 || ! command -v scrot >/dev/null 2>&1 || ! command -v openbox >/dev/null 2>&1 || ! command -v xclip >/dev/null 2>&1; then " +
                    "  (sudo -n apt-get update -qq && sudo -n apt-get install -y -qq xvfb openbox xdotool scrot xclip x11-apps fonts-liberation) " +
                    "  || (apt-get update -qq && apt-get install -y -qq xvfb openbox xdotool scrot xclip x11-apps fonts-liberation) || true; " +
                    "fi; " +
                    "if ! pgrep -f 'Xvfb " + disp + "' >/dev/null 2>&1; then " +
                    "  nohup Xvfb " + disp + " -screen 0 " + screenWidth + "x" + screenHeight + "x24 -ac >/tmp/mkpro_xvfb.log 2>&1 & " +
                    "  sleep 1; " +
                    "fi; " +
                    "if ! pgrep -f 'openbox' >/dev/null 2>&1; then " +
                    "  DISPLAY=" + disp + " nohup openbox >/tmp/mkpro_openbox.log 2>&1 & " +
                    "  sleep 0.5; " +
                    "fi; " +
                    "pgrep -a Xvfb || true";

            SshSessionManager.CommandResult res = ssh.executeCommand(resolved, bootstrapScript, 120);
            this.mode = "vm";
            this.lastAction = "init_vm_desktop(" + disp + ")";
            this.lastUpdatedEpochMs = System.currentTimeMillis();

            Map<String, Object> out = getStatus(resolved);
            out.put("status", "ok");
            out.put("bootstrapStdout", res.getStdout().trim());
            out.put("bootstrapStderr", res.getStderr().trim());
            return out;
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("error", "Failed to initialize VM desktop: " + e.getMessage());
            return err;
        }
    }

    /**
     * Returns current status of the VM/Local desktop sandbox.
     */
    public Map<String, Object> getStatus(String alias) {
        String resolved = resolveAlias(alias);
        SshSessionManager ssh = SshSessionManager.getInstance();
        boolean sshConnected = ssh.hasActiveSession(resolved);
        boolean xvfbRunning = false;

        if (sshConnected) {
            try {
                String disp = sanitizeDisplay(this.display);
                SshSessionManager.CommandResult check = ssh.executeCommand(
                        resolved,
                        "pgrep -f 'Xvfb " + disp + "' >/dev/null 2>&1 && echo RUNNING || echo STOPPED",
                        10
                );
                xvfbRunning = check.getStdout() != null && check.getStdout().contains("RUNNING");
            } catch (Exception ignored) {}
        }

        int width = screenWidth;
        int height = screenHeight;
        if ("local".equalsIgnoreCase(mode) && !GraphicsEnvironment.isHeadless()) {
            try {
                Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
                width = d.width;
                height = d.height;
            } catch (Exception ignored) {}
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connected", sshConnected || ("local".equalsIgnoreCase(mode) && !GraphicsEnvironment.isHeadless()));
        status.put("sshConnected", sshConnected);
        status.put("alias", resolved);
        status.put("mode", mode);
        status.put("display", display);
        status.put("width", width);
        status.put("height", height);
        status.put("xvfbRunning", xvfbRunning);
        status.put("lastScreenshotPath", lastScreenshotPath);
        status.put("lastAction", lastAction);
        status.put("lastUpdatedEpochMs", lastUpdatedEpochMs);
        status.put("hasFrame", lastFrameBase64 != null && !lastFrameBase64.isEmpty());
        return status;
    }

    /**
     * Captures a screenshot from the remote VM display (:99) or local desktop,
     * annotates it with the 0..1000 grid and optional action crosshair, attaches it
     * to AgentManager.currentTurnMediaParts, emits a system event, and returns metadata.
     */
    public Map<String, Object> captureScreenshot(
            String alias,
            Integer markNormX,
            Integer markNormY,
            String actionLabel,
            boolean showGrid
    ) {
        String resolved = resolveAlias(alias);
        Path capturesDir = PathUtils.getMkproDataDir().resolve("captures");
        PathUtils.ensureDirectory(capturesDir);
        long ts = Instant.now().toEpochMilli();
        Path rawPath = capturesDir.resolve("vm_screen_raw_" + ts + ".png");
        Path annotatedPath = capturesDir.resolve("vm_screen_" + ts + ".png");

        BufferedImage rawImage = null;
        String activeMode = isVmConnected(resolved) ? "vm" : "local";

        try {
            if ("vm".equals(activeMode)) {
                SshSessionManager ssh = SshSessionManager.getInstance();
                String disp = sanitizeDisplay(this.display);
                String remoteTmp = "/tmp/mkpro_vm_screen.png";
                String captureCmd = String.format(
                        "DISPLAY=%s scrot -o %s 2>/dev/null || DISPLAY=%s import -window root %s",
                        disp, remoteTmp, disp, remoteTmp
                );
                SshSessionManager.CommandResult cmdRes = ssh.executeCommand(resolved, captureCmd, 20);
                if (!cmdRes.isSuccess()) {
                    // Try auto-starting Xvfb if not running yet
                    initVmDesktop(resolved);
                    ssh.executeCommand(resolved, captureCmd, 20);
                }
                ssh.downloadFile(resolved, remoteTmp, rawPath.toAbsolutePath().toString());
                rawImage = ImageIO.read(rawPath.toFile());
                try { Files.deleteIfExists(rawPath); } catch (Exception ignored) {}
            } else {
                if (GraphicsEnvironment.isHeadless()) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("status", "error");
                    err.put("error", "No active SSH session and local environment is headless. Connect SSH (/ssh connect) or run with a display.");
                    return err;
                }
                checkLocalFailsafe();
                Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
                this.screenWidth = screenSize.width;
                this.screenHeight = screenSize.height;
                Robot robot = new Robot();
                rawImage = robot.createScreenCapture(new Rectangle(screenSize));
            }

            if (rawImage == null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("status", "error");
                err.put("error", "Failed to decode captured screenshot image.");
                return err;
            }

            this.screenWidth = rawImage.getWidth();
            this.screenHeight = rawImage.getHeight();

            BufferedImage annotated = ScreenGridAnnotator.annotateWithGridAndCrosshair(
                    rawImage, markNormX, markNormY, actionLabel, showGrid
            );
            ImageIO.write(annotated, "png", annotatedPath.toFile());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(annotated, "png", baos);
            byte[] pngBytes = baos.toByteArray();
            String base64 = Base64.getEncoder().encodeToString(pngBytes);

            setLastFrame(base64, annotatedPath.toAbsolutePath().toString(),
                    actionLabel != null && !actionLabel.isBlank() ? actionLabel : "screenshot");

            // Attach inline image part to current turn for Vision-capable LLMs
            if (AgentManager.currentTurnMediaParts != null) {
                Part imagePart = Part.builder()
                        .inlineData(Blob.builder()
                                .mimeType("image/png")
                                .data(pngBytes)
                                .build())
                        .build();
                AgentManager.currentTurnMediaParts.add(imagePart);
            }

            if (MkProEventBus.INSTANCE != null) {
                MkProEventBus.INSTANCE.emit(MkProEvent.system(
                        "[ComputerUse] Captured " + activeMode.toUpperCase(Locale.ROOT) + " frame (" +
                        screenWidth + "x" + screenHeight + ") -> " + annotatedPath.getFileName() +
                        (actionLabel != null && !actionLabel.isBlank() ? " [" + actionLabel + "]" : "")
                ));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("mode", activeMode);
            result.put("alias", resolved);
            result.put("display", display);
            result.put("width", screenWidth);
            result.put("height", screenHeight);
            result.put("screenshot_path", annotatedPath.toAbsolutePath().toString());
            result.put("action_label", actionLabel != null ? actionLabel : "screenshot");
            if (markNormX != null && markNormY != null) {
                result.put("norm_x", markNormX);
                result.put("norm_y", markNormY);
                result.put("pixel_x", ScreenGridAnnotator.normToPixelX(markNormX, screenWidth));
                result.put("pixel_y", ScreenGridAnnotator.normToPixelY(markNormY, screenHeight));
            }
            return result;
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("error", "Screenshot capture failed: " + e.getMessage());
            return err;
        }
    }

    /**
     * Clicks at normalized [0..1000] coordinates (normX, normY) and captures an annotated verification screenshot.
     */
    public Map<String, Object> click(String alias, int normX, int normY, int button, boolean doubleClick) {
        String resolved = resolveAlias(alias);
        int btn = (button >= 1 && button <= 3) ? button : 1;
        String label = (doubleClick ? "dblclick" : "click") + "(" + normX + "," + normY + ",btn=" + btn + ")";

        try {
            if (isVmConnected(resolved)) {
                int px = ScreenGridAnnotator.normToPixelX(normX, screenWidth);
                int py = ScreenGridAnnotator.normToPixelY(normY, screenHeight);
                String cmd = buildClickCommand(display, px, py, btn, doubleClick);
                SshSessionManager.getInstance().executeCommand(resolved, cmd, 15);
            } else {
                if (GraphicsEnvironment.isHeadless()) {
                    return Map.of("status", "error", "error", "No active SSH session and local environment is headless.");
                }
                checkLocalFailsafe();
                Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
                this.screenWidth = d.width;
                this.screenHeight = d.height;
                int px = ScreenGridAnnotator.normToPixelX(normX, screenWidth);
                int py = ScreenGridAnnotator.normToPixelY(normY, screenHeight);
                Robot robot = new Robot();
                robot.mouseMove(px, py);
                robot.delay(60);
                int mask = (btn == 3) ? InputEvent.BUTTON3_DOWN_MASK
                        : (btn == 2) ? InputEvent.BUTTON2_DOWN_MASK
                        : InputEvent.BUTTON1_DOWN_MASK;
                int count = doubleClick ? 2 : 1;
                for (int i = 0; i < count; i++) {
                    robot.mousePress(mask);
                    robot.delay(40);
                    robot.mouseRelease(mask);
                    if (i + 1 < count) robot.delay(80);
                }
            }
            Thread.sleep(250);
            return captureScreenshot(resolved, normX, normY, label, true);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Click failed: " + e.getMessage());
        }
    }

    /**
     * Moves or drags from normalized (fromNormX, fromNormY) to (toNormX, toNormY).
     */
    public Map<String, Object> moveOrDrag(String alias, int fromNormX, int fromNormY, int toNormX, int toNormY, boolean drag) {
        String resolved = resolveAlias(alias);
        String label = (drag ? "drag(" + fromNormX + "," + fromNormY + "->" : "move(") + toNormX + "," + toNormY + ")";

        try {
            if (isVmConnected(resolved)) {
                int fpx = ScreenGridAnnotator.normToPixelX(fromNormX, screenWidth);
                int fpy = ScreenGridAnnotator.normToPixelY(fromNormY, screenHeight);
                int tpx = ScreenGridAnnotator.normToPixelX(toNormX, screenWidth);
                int tpy = ScreenGridAnnotator.normToPixelY(toNormY, screenHeight);
                String cmd = buildMoveOrDragCommand(display, fpx, fpy, tpx, tpy, drag);
                SshSessionManager.getInstance().executeCommand(resolved, cmd, 15);
            } else {
                if (GraphicsEnvironment.isHeadless()) {
                    return Map.of("status", "error", "error", "No active SSH session and local environment is headless.");
                }
                checkLocalFailsafe();
                Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
                this.screenWidth = d.width;
                this.screenHeight = d.height;
                int fpx = ScreenGridAnnotator.normToPixelX(fromNormX, screenWidth);
                int fpy = ScreenGridAnnotator.normToPixelY(fromNormY, screenHeight);
                int tpx = ScreenGridAnnotator.normToPixelX(toNormX, screenWidth);
                int tpy = ScreenGridAnnotator.normToPixelY(toNormY, screenHeight);
                Robot robot = new Robot();
                if (drag) {
                    robot.mouseMove(fpx, fpy);
                    robot.delay(80);
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.delay(80);
                    robot.mouseMove(tpx, tpy);
                    robot.delay(80);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                } else {
                    robot.mouseMove(tpx, tpy);
                }
            }
            Thread.sleep(200);
            return captureScreenshot(resolved, toNormX, toNormY, label, true);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Move/drag failed: " + e.getMessage());
        }
    }

    /**
     * Scrolls at normalized (normX, normY) in direction ("up", "down", "left", "right").
     */
    public Map<String, Object> scroll(String alias, int normX, int normY, String direction, int clicks) {
        String resolved = resolveAlias(alias);
        int count = Math.max(1, Math.min(25, clicks <= 0 ? 3 : clicks));
        String dir = (direction == null || direction.isBlank()) ? "down" : direction.trim().toLowerCase(Locale.ROOT);
        String label = "scroll(" + dir + " x" + count + " @" + normX + "," + normY + ")";

        try {
            if (isVmConnected(resolved)) {
                int px = ScreenGridAnnotator.normToPixelX(normX, screenWidth);
                int py = ScreenGridAnnotator.normToPixelY(normY, screenHeight);
                String cmd = buildScrollCommand(display, px, py, dir, count);
                SshSessionManager.getInstance().executeCommand(resolved, cmd, 15);
            } else {
                if (GraphicsEnvironment.isHeadless()) {
                    return Map.of("status", "error", "error", "No active SSH session and local environment is headless.");
                }
                checkLocalFailsafe();
                Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
                this.screenWidth = d.width;
                this.screenHeight = d.height;
                int px = ScreenGridAnnotator.normToPixelX(normX, screenWidth);
                int py = ScreenGridAnnotator.normToPixelY(normY, screenHeight);
                Robot robot = new Robot();
                robot.mouseMove(px, py);
                robot.delay(50);
                int wheelAmt = "up".equals(dir) ? -count : count;
                robot.mouseWheel(wheelAmt);
            }
            Thread.sleep(250);
            return captureScreenshot(resolved, normX, normY, label, true);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Scroll failed: " + e.getMessage());
        }
    }

    /**
     * Types text into the active window using clipboard paste (or fallback typing) and optional Enter key.
     */
    public Map<String, Object> type(String alias, String text, boolean pressEnter) {
        String resolved = resolveAlias(alias);
        String preview = text != null ? (text.length() > 20 ? text.substring(0, 20) + "..." : text) : "";
        String label = "type(\"" + preview + "\"" + (pressEnter ? "+Enter" : "") + ")";

        try {
            if (isVmConnected(resolved)) {
                String cmd = buildTypeCommand(display, text, pressEnter);
                SshSessionManager.getInstance().executeCommand(resolved, cmd, 20);
            } else {
                if (GraphicsEnvironment.isHeadless()) {
                    return Map.of("status", "error", "error", "No active SSH session and local environment is headless.");
                }
                checkLocalFailsafe();
                Robot robot = new Robot();
                StringSelection selection = new StringSelection(text != null ? text : "");
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
                boolean isMac = PathUtils.isMac();
                int modKey = isMac ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
                robot.keyPress(modKey);
                robot.keyPress(KeyEvent.VK_V);
                robot.delay(40);
                robot.keyRelease(KeyEvent.VK_V);
                robot.keyRelease(modKey);
                if (pressEnter) {
                    robot.delay(60);
                    robot.keyPress(KeyEvent.VK_ENTER);
                    robot.delay(40);
                    robot.keyRelease(KeyEvent.VK_ENTER);
                }
            }
            Thread.sleep(250);
            return captureScreenshot(resolved, null, null, label, true);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Type failed: " + e.getMessage());
        }
    }

    /**
     * Sends a key or key combination (e.g., "Return", "ctrl+l", "alt+F4", "Escape").
     */
    public Map<String, Object> key(String alias, String keyCombo) {
        String resolved = resolveAlias(alias);
        String label = "key(" + keyCombo + ")";

        try {
            if (isVmConnected(resolved)) {
                String cmd = buildKeyCommand(display, keyCombo);
                SshSessionManager.getInstance().executeCommand(resolved, cmd, 15);
            } else {
                if (GraphicsEnvironment.isHeadless()) {
                    return Map.of("status", "error", "error", "No active SSH session and local environment is headless.");
                }
                checkLocalFailsafe();
                Robot robot = new Robot();
                pressLocalKeyCombo(robot, keyCombo);
            }
            Thread.sleep(250);
            return captureScreenshot(resolved, null, null, label, true);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Key command failed: " + e.getMessage());
        }
    }

    /**
     * Launches a GUI application on the remote VM display (:99) in the background and captures a screenshot.
     */
    public Map<String, Object> launchApp(String alias, String command) {
        String resolved = resolveAlias(alias);
        if (command == null || command.isBlank()) {
            return Map.of("status", "error", "error", "Application command cannot be empty.");
        }
        String label = "launch(" + command.trim() + ")";

        try {
            if (isVmConnected(resolved)) {
                String disp = sanitizeDisplay(this.display);
                String runCmd = String.format("DISPLAY=%s nohup %s >/tmp/mkpro_app.log 2>&1 & sleep 1", disp, command.trim());
                SshSessionManager.getInstance().executeCommand(resolved, runCmd, 15);
            } else {
                new ProcessBuilder("sh", "-c", command.trim()).start();
                Thread.sleep(1000);
            }
            return captureScreenshot(resolved, null, null, label, true);
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Failed to launch app: " + e.getMessage());
        }
    }

    /**
     * Emergency 4-corner failsafe for local Robot execution:
     * If the physical mouse cursor is within 8px of any screen corner, aborts immediately.
     */
    private void checkLocalFailsafe() {
        if (GraphicsEnvironment.isHeadless()) return;
        PointerInfo info = MouseInfo.getPointerInfo();
        if (info == null) return;
        Point p = info.getLocation();
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        boolean inLeft = p.x <= FAILSAFE_CORNER_MARGIN_PX;
        boolean inRight = p.x >= d.width - 1 - FAILSAFE_CORNER_MARGIN_PX;
        boolean inTop = p.y <= FAILSAFE_CORNER_MARGIN_PX;
        boolean inBottom = p.y >= d.height - 1 - FAILSAFE_CORNER_MARGIN_PX;
        if ((inLeft || inRight) && (inTop || inBottom)) {
            throw new IllegalStateException(
                    "LOCAL FAILSAFE TRIGGERED: Mouse pointer is in screen corner (" + p.x + "," + p.y + "). Aborting local computer control."
            );
        }
    }

    private void pressLocalKeyCombo(Robot robot, String keyCombo) {
        if (keyCombo == null || keyCombo.isBlank()) return;
        String[] parts = keyCombo.trim().split("\\+");
        List<Integer> codes = new ArrayList<>();
        for (String part : parts) {
            int code = mapKeyCode(part.trim());
            if (code != KeyEvent.VK_UNDEFINED) {
                codes.add(code);
            }
        }
        for (int code : codes) {
            robot.keyPress(code);
            robot.delay(25);
        }
        for (int i = codes.size() - 1; i >= 0; i--) {
            robot.keyRelease(codes.get(i));
            robot.delay(25);
        }
    }

    private int mapKeyCode(String key) {
        if (key == null || key.isEmpty()) return KeyEvent.VK_UNDEFINED;
        String lower = key.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "enter":
            case "return":
                return KeyEvent.VK_ENTER;
            case "esc":
            case "escape":
                return KeyEvent.VK_ESCAPE;
            case "tab":
                return KeyEvent.VK_TAB;
            case "space":
                return KeyEvent.VK_SPACE;
            case "backspace":
                return KeyEvent.VK_BACK_SPACE;
            case "delete":
            case "del":
                return KeyEvent.VK_DELETE;
            case "up":
                return KeyEvent.VK_UP;
            case "down":
                return KeyEvent.VK_DOWN;
            case "left":
                return KeyEvent.VK_LEFT;
            case "right":
                return KeyEvent.VK_RIGHT;
            case "ctrl":
            case "control":
                return KeyEvent.VK_CONTROL;
            case "alt":
                return KeyEvent.VK_ALT;
            case "shift":
                return KeyEvent.VK_SHIFT;
            case "super":
            case "meta":
            case "cmd":
            case "win":
                return KeyEvent.VK_META;
            default:
                if (lower.length() == 1) {
                    return KeyEvent.getExtendedKeyCodeForChar(lower.charAt(0));
                }
                return KeyEvent.VK_UNDEFINED;
        }
    }
}
