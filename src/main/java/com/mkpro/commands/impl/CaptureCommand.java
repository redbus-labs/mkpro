package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.utils.PathUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static com.mkpro.MkPro.*;

/**
 * /capture command — captures a multi-monitor screenshot using Java AWT Robot
 * with OS-level CLI fallbacks (PowerShell on Windows, screencapture on macOS,
 * gnome-screenshot/scrot/grim/import on Linux), saving to .mkpro/captures/.
 */
public class CaptureCommand implements Command {

    @Override
    public String getName() {
        return "capture";
    }

    @Override
    public String getDescription() {
        return "Capture multi-monitor screenshot to .mkpro/captures/.";
    }

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        Path capturesDir = PathUtils.getMkproDataDir().resolve("captures");
        Files.createDirectories(capturesDir);

        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        String fileName = "screenshot_" + timestamp + ".png";
        Path targetPath = capturesDir.resolve(fileName);
        File targetFile = targetPath.toFile();

        int width = 0;
        int height = 0;
        boolean success = false;

        // 1. Try Java AWT Robot multi-monitor screen capture
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                GraphicsDevice[] screens = ge.getScreenDevices();
                Rectangle virtualBounds = new Rectangle();

                for (GraphicsDevice screen : screens) {
                    GraphicsConfiguration gc = screen.getDefaultConfiguration();
                    virtualBounds = virtualBounds.union(gc.getBounds());
                }

                if (virtualBounds.width > 0 && virtualBounds.height > 0) {
                    Robot robot = new Robot();
                    BufferedImage screenCapture = robot.createScreenCapture(virtualBounds);
                    ImageIO.write(screenCapture, "png", targetFile);
                    width = screenCapture.getWidth();
                    height = screenCapture.getHeight();
                    success = targetFile.exists() && targetFile.length() > 0;
                }
            }
        } catch (Throwable t) {
            // Fall back to OS CLI
        }

        // 2. OS Fallback if Robot capture was unsuccessful
        if (!success) {
            success = captureWithOsCli(targetFile);
            if (success && targetFile.exists()) {
                try {
                    BufferedImage img = ImageIO.read(targetFile);
                    if (img != null) {
                        width = img.getWidth();
                        height = img.getHeight();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (!success || !targetFile.exists()) {
            System.out.println(ANSI_RED + "✘ Screen capture failed. Ensure display environment or screenshot tools are available." + ANSI_RESET);
            return;
        }

        long fileSize = Files.size(targetPath);
        Path projectRoot = PathUtils.getProjectPath();
        String relativePath;
        try {
            relativePath = projectRoot.relativize(targetPath).toString().replace('\\', '/');
        } catch (Exception e) {
            relativePath = ".mkpro/captures/" + fileName;
        }

        String sizeFormatted = formatFileSize(fileSize);
        String dimsFormatted = (width > 0 && height > 0) ? (width + " × " + height + " px") : "Auto-detected";

        PrintStream out = System.out;
        out.println("📸 **Screen capture saved successfully!**\n");
        out.println("• **File:** `" + fileName + "`");
        out.println("• **Relative Path:** `" + relativePath + "`");
        out.println("• **Dimensions:** " + dimsFormatted);
        out.println("• **Size:** " + sizeFormatted + "\n");
        out.println("<div style=\"margin: 10px 0 4px 0;\">"
            + "<button class=\"btn btn-sm btn-primary\" style=\"display:inline-flex;align-items:center;gap:6px;padding:6px 14px;border-radius:6px;cursor:pointer;background:var(--accent,#2563eb);color:#fff;border:none;font-weight:600;font-size:13px;\" "
            + "onclick=\"inspectFile('" + relativePath + "', '" + fileName + "')\">👁️ View Screenshot</button>"
            + "</div>");
    }

    private boolean captureWithOsCli(File targetFile) {
        String absPath = targetFile.getAbsolutePath();
        if (PathUtils.isWindows()) {
            try {
                String psCommand = String.format(
                    "Add-Type -AssemblyName System.Windows.Forms,System.Drawing; " +
                    "$screen = [System.Windows.Forms.SystemInformation]::VirtualScreen; " +
                    "$bmp = New-Object System.Drawing.Bitmap $screen.Width, $screen.Height; " +
                    "$g = [System.Drawing.Graphics]::FromImage($bmp); " +
                    "$g.CopyFromScreen($screen.Left, $screen.Top, 0, 0, $screen.Size); " +
                    "$bmp.Save('%s', [System.Drawing.Imaging.ImageFormat]::Png); " +
                    "$g.Dispose(); $bmp.Dispose();",
                    absPath.replace("'", "''")
                );
                java.lang.ProcessBuilder pb = new java.lang.ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand);
                java.lang.Process p = pb.start();
                boolean finished = p.waitFor(10, TimeUnit.SECONDS);
                return finished && p.exitValue() == 0 && targetFile.exists() && targetFile.length() > 0;
            } catch (Exception e) {
                return false;
            }
        } else if (PathUtils.isMac()) {
            try {
                java.lang.ProcessBuilder pb = new java.lang.ProcessBuilder("screencapture", "-x", absPath);
                java.lang.Process p = pb.start();
                boolean finished = p.waitFor(10, TimeUnit.SECONDS);
                return finished && p.exitValue() == 0 && targetFile.exists() && targetFile.length() > 0;
            } catch (Exception e) {
                return false;
            }
        } else if (PathUtils.isLinux()) {
            String[] tools = {
                "gnome-screenshot -f \"" + absPath + "\"",
                "scrot \"" + absPath + "\"",
                "grim \"" + absPath + "\"",
                "import -window root \"" + absPath + "\""
            };
            for (String toolCmd : tools) {
                try {
                    java.lang.ProcessBuilder pb = new java.lang.ProcessBuilder("sh", "-c", toolCmd);
                    java.lang.Process p = pb.start();
                    boolean finished = p.waitFor(10, TimeUnit.SECONDS);
                    if (finished && p.exitValue() == 0 && targetFile.exists() && targetFile.length() > 0) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }
}