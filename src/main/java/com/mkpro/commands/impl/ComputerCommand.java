package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.infra.computer.VmDesktopSandbox;
import com.mkpro.tools.BrowserUseTools;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;

import static com.mkpro.ui.AnsiColors.*;

/**
 * /computer CLI command for VM-First & Local Fallback Computer Use and Browser Use:
 * - /computer status
 * - /computer init [alias]
 * - /computer mode <vm|local>
 * - /computer screenshot [alias]
 * - /computer click <x> <y> [alias]
 * - /computer type <text>
 * - /computer key <combo>
 * - /computer launch <command>
 * - /computer open <url>
 */
public class ComputerCommand implements Command {

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        PrintWriter writer = (context != null && context.getTerminal() != null)
                ? context.getTerminal().writer()
                : new PrintWriter(System.out, true);
        VmDesktopSandbox sandbox = VmDesktopSandbox.getInstance();

        if (args == null || args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            String alias = (args != null && args.length > 1) ? args[1] : "default";
            Map<String, Object> st = sandbox.getStatus(alias);
            writer.println(BOLD + CYAN + "Computer Use & VM Desktop Sandbox Status:" + RESET);
            writer.println("  Mode:               " + BRIGHT_GREEN + st.get("mode") + RESET);
            writer.println("  SSH Alias:          " + st.get("alias") + " (SSH connected: " + st.get("sshConnected") + ")");
            writer.println("  X11 Display:        " + st.get("display") + " (" + st.get("width") + "x" + st.get("height") + ")");
            writer.println("  Xvfb Running:       " + (Boolean.TRUE.equals(st.get("xvfbRunning")) ? GREEN + "YES" : YELLOW + "NO") + RESET);
            writer.println("  Last Action:        " + st.get("lastAction"));
            writer.println("  Last Screenshot:    " + (st.get("lastScreenshotPath") != null && !String.valueOf(st.get("lastScreenshotPath")).isEmpty()
                    ? st.get("lastScreenshotPath") : DIM + "(none)" + RESET));
            writer.println(DIM + "Subcommands: /computer init | /computer mode <vm|local> | /computer screenshot | /computer click <x> <y> | /computer type <text> | /computer key <combo> | /computer launch <cmd> | /computer open <url>" + RESET);
            writer.flush();
            return;
        }

        String sub = args[0].trim().toLowerCase();

        switch (sub) {
            case "init": {
                String alias = args.length > 1 ? args[1] : "default";
                writer.println(BLUE + "[ComputerUse] Initializing remote VM X11 desktop (:99, 1280x800) on alias '" + alias + "'..." + RESET);
                writer.flush();
                Map<String, Object> res = sandbox.initVmDesktop(alias);
                if ("ok".equals(res.get("status"))) {
                    writer.println(GREEN + "[ComputerUse] VM Desktop ready on display " + res.get("display") +
                            " (Xvfb running: " + res.get("xvfbRunning") + ")" + RESET);
                } else {
                    writer.println(RED + "[ComputerUse] " + res.getOrDefault("error", "Initialization failed") + RESET);
                }
                writer.flush();
                return;
            }
            case "mode": {
                if (args.length < 2) {
                    writer.println("Current mode: " + BOLD + sandbox.getMode() + RESET + " (Usage: /computer mode <vm|local>)");
                } else {
                    String m = args[1].trim().toLowerCase();
                    if (!"vm".equals(m) && !"local".equals(m)) {
                        writer.println(RED + "Invalid mode '" + m + "'. Choose 'vm' or 'local'." + RESET);
                    } else {
                        sandbox.setMode(m);
                        writer.println(GREEN + "[ComputerUse] Execution mode set to: " + m.toUpperCase() + RESET);
                    }
                }
                writer.flush();
                return;
            }
            case "screenshot":
            case "snap": {
                String alias = args.length > 1 ? args[1] : "default";
                Map<String, Object> res = sandbox.captureScreenshot(alias, null, null, "cli_screenshot", true);
                if ("ok".equals(res.get("status"))) {
                    writer.println(GREEN + "[ComputerUse] Screenshot saved: " + res.get("screenshot_path") +
                            " (" + res.get("width") + "x" + res.get("height") + ")" + RESET);
                } else {
                    writer.println(RED + "[ComputerUse] " + res.getOrDefault("error", "Screenshot failed") + RESET);
                }
                writer.flush();
                return;
            }
            case "click": {
                if (args.length < 3) {
                    writer.println(YELLOW + "Usage: /computer click <normX 0..1000> <normY 0..1000> [alias]" + RESET);
                    writer.flush();
                    return;
                }
                int x = Integer.parseInt(args[1]);
                int y = Integer.parseInt(args[2]);
                String alias = args.length > 3 ? args[3] : "default";
                Map<String, Object> res = sandbox.click(alias, x, y, 1, false);
                if ("ok".equals(res.get("status"))) {
                    writer.println(GREEN + "[ComputerUse] Clicked (" + x + "," + y + ") -> " + res.get("screenshot_path") + RESET);
                } else {
                    writer.println(RED + "[ComputerUse] " + res.getOrDefault("error", "Click failed") + RESET);
                }
                writer.flush();
                return;
            }
            case "type": {
                if (args.length < 2) {
                    writer.println(YELLOW + "Usage: /computer type <text>" + RESET);
                    writer.flush();
                    return;
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Map<String, Object> res = sandbox.type("default", text, false);
                if ("ok".equals(res.get("status"))) {
                    writer.println(GREEN + "[ComputerUse] Typed text -> " + res.get("screenshot_path") + RESET);
                } else {
                    writer.println(RED + "[ComputerUse] " + res.getOrDefault("error", "Type failed") + RESET);
                }
                writer.flush();
                return;
            }
            case "key": {
                if (args.length < 2) {
                    writer.println(YELLOW + "Usage: /computer key <keyCombo> (e.g., Return, Escape, ctrl+l)" + RESET);
                    writer.flush();
                    return;
                }
                Map<String, Object> res = sandbox.key("default", args[1]);
                if ("ok".equals(res.get("status"))) {
                    writer.println(GREEN + "[ComputerUse] Sent key '" + args[1] + "' -> " + res.get("screenshot_path") + RESET);
                } else {
                    writer.println(RED + "[ComputerUse] " + res.getOrDefault("error", "Key failed") + RESET);
                }
                writer.flush();
                return;
            }
            case "launch": {
                if (args.length < 2) {
                    writer.println(YELLOW + "Usage: /computer launch <command>" + RESET);
                    writer.flush();
                    return;
                }
                String cmd = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Map<String, Object> res = sandbox.launchApp("default", cmd);
                if ("ok".equals(res.get("status"))) {
                    writer.println(GREEN + "[ComputerUse] Launched '" + cmd + "' -> " + res.get("screenshot_path") + RESET);
                } else {
                    writer.println(RED + "[ComputerUse] " + res.getOrDefault("error", "Launch failed") + RESET);
                }
                writer.flush();
                return;
            }
            case "open": {
                if (args.length < 2) {
                    writer.println(YELLOW + "Usage: /computer open <url>" + RESET);
                    writer.flush();
                    return;
                }
                String url = args[1];
                Map<String, Object> res = BrowserUseTools.createOpenTool()
                        .runAsync(Map.of("url", url), null)
                        .blockingGet();
                if ("ok".equals(res.get("status"))) {
                    writer.println(GREEN + "[BrowserUse] Opened " + res.get("url") + " (" +
                            res.getOrDefault("element_count", 0) + " SoM elements) -> " + res.get("screenshot_path") + RESET);
                } else {
                    writer.println(RED + "[BrowserUse] " + res.getOrDefault("error", "Open failed") + RESET);
                }
                writer.flush();
                return;
            }
            default: {
                writer.println(YELLOW + "Unknown subcommand: " + sub + RESET);
                writer.println("Usage: /computer [status|init|mode|screenshot|click|type|key|launch|open]");
                writer.flush();
            }
        }
    }

    @Override
    public String getName() {
        return "computer";
    }

    @Override
    public String getDescription() {
        return "Manage VM/Local Computer Use & Set-of-Mark Browser Use (/computer status, /computer init, /computer mode, /computer screenshot, /computer click, /computer open)";
    }
}
