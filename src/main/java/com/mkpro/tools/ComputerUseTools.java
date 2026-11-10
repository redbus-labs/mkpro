package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.mkpro.infra.computer.VmDesktopSandbox;
import io.reactivex.rxjava3.core.Single;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ComputerUseTools exposes normalized [0..1000] GUI desktop automation tools
 * backed by VmDesktopSandbox (VM-First on Ubuntu Xvfb :99 via SSH, with Local Robot fallback):
 * - computer_init_vm
 * - computer_screenshot
 * - computer_click
 * - computer_move_or_drag
 * - computer_type
 * - computer_key
 * - computer_scroll
 * - computer_launch_app
 */
public class ComputerUseTools {

    private static final VmDesktopSandbox sandbox = VmDesktopSandbox.getInstance();

    private static int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof String) {
            try {
                return Integer.parseInt(((String) val).trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static boolean getBoolArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object val = args.get(key);
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        if (val instanceof String) {
            String s = ((String) val).trim().toLowerCase();
            return "true".equals(s) || "1".equals(s) || "yes".equals(s);
        }
        return defaultValue;
    }

    private static String getAliasArg(Map<String, Object> args) {
        String alias = (String) args.get("alias");
        if (alias == null || alias.isBlank()) {
            alias = (String) args.get("sessionAlias");
        }
        return (alias == null || alias.isBlank()) ? "default" : alias.trim();
    }

    public static BaseTool createInitVmTool() {
        return new BaseTool(
                "computer_init_vm",
                "Initializes the remote Ubuntu VM virtual desktop environment (Xvfb :99 at 1280x800 + Openbox + xdotool + scrot + xclip) over the active SSH session."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "alias", Schema.builder().type("STRING").description("SSH session alias (default: 'default').").build()
                                ))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> sandbox.initVmDesktop(getAliasArg(args)));
            }
        };
    }

    public static BaseTool createScreenshotTool() {
        return new BaseTool(
                "computer_screenshot",
                "Captures a screenshot of the VM (:99) or local desktop, overlays a 0..1000 normalized coordinate grid, attaches the image inline for Vision analysis, and returns the file path and resolution."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "alias", Schema.builder().type("STRING").description("SSH session alias (default: 'default').").build(),
                                        "show_grid", Schema.builder().type("BOOLEAN").description("Whether to draw the 0..1000 coordinate grid overlay (default: true).").build()
                                ))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    boolean showGrid = getBoolArg(args, "show_grid", true);
                    return sandbox.captureScreenshot(getAliasArg(args), null, null, "screenshot", showGrid);
                });
            }
        };
    }

    public static BaseTool createClickTool() {
        return new BaseTool(
                "computer_click",
                "Clicks at normalized screen coordinates (x, y) in [0..1000] on the VM or local desktop, then automatically captures an annotated verification screenshot with a red crosshair at the click target."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.<String, Schema>builder()
                                        .put("x", Schema.builder().type("INTEGER").description("Normalized X coordinate in [0..1000] (0=left edge, 500=center, 1000=right edge).").build())
                                        .put("y", Schema.builder().type("INTEGER").description("Normalized Y coordinate in [0..1000] (0=top edge, 500=center, 1000=bottom edge).").build())
                                        .put("button", Schema.builder().type("INTEGER").description("Mouse button: 1=left (default), 2=middle, 3=right.").build())
                                        .put("double_click", Schema.builder().type("BOOLEAN").description("If true, performs a double-click (default: false).").build())
                                        .put("alias", Schema.builder().type("STRING").description("SSH session alias (default: 'default').").build())
                                        .build())
                                .required(ImmutableList.of("x", "y"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    int x = getIntArg(args, "x", 500);
                    int y = getIntArg(args, "y", 500);
                    int button = getIntArg(args, "button", 1);
                    boolean doubleClick = getBoolArg(args, "double_click", false);
                    return sandbox.click(getAliasArg(args), x, y, button, doubleClick);
                });
            }
        };
    }

    public static BaseTool createMoveOrDragTool() {
        return new BaseTool(
                "computer_move_or_drag",
                "Moves the mouse cursor or drags from (from_x, from_y) to (to_x, to_y) in normalized [0..1000] coordinates, then captures an annotated verification screenshot."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.<String, Schema>builder()
                                        .put("from_x", Schema.builder().type("INTEGER").description("Starting normalized X in [0..1000] (optional if drag=false).").build())
                                        .put("from_y", Schema.builder().type("INTEGER").description("Starting normalized Y in [0..1000] (optional if drag=false).").build())
                                        .put("to_x", Schema.builder().type("INTEGER").description("Target normalized X in [0..1000].").build())
                                        .put("to_y", Schema.builder().type("INTEGER").description("Target normalized Y in [0..1000].").build())
                                        .put("drag", Schema.builder().type("BOOLEAN").description("True to hold left mouse button while moving (drag-and-drop); false to just move cursor.").build())
                                        .put("alias", Schema.builder().type("STRING").description("SSH session alias (default: 'default').").build())
                                        .build())
                                .required(ImmutableList.of("to_x", "to_y"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    int toX = getIntArg(args, "to_x", 500);
                    int toY = getIntArg(args, "to_y", 500);
                    int fromX = getIntArg(args, "from_x", toX);
                    int fromY = getIntArg(args, "from_y", toY);
                    boolean drag = getBoolArg(args, "drag", false);
                    return sandbox.moveOrDrag(getAliasArg(args), fromX, fromY, toX, toY, drag);
                });
            }
        };
    }

    public static BaseTool createTypeTool() {
        return new BaseTool(
                "computer_type",
                "Types arbitrary text (including symbols, URLs, and multi-line strings) into the currently focused element on the VM or local desktop, optionally pressing Enter afterwards, then captures a screenshot."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.<String, Schema>builder()
                                        .put("text", Schema.builder().type("STRING").description("The text string to type.").build())
                                        .put("press_enter", Schema.builder().type("BOOLEAN").description("Whether to press Return/Enter after typing (default: false).").build())
                                        .put("alias", Schema.builder().type("STRING").description("SSH session alias (default: 'default').").build())
                                        .build())
                                .required(ImmutableList.of("text"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String text = args.get("text") != null ? String.valueOf(args.get("text")) : "";
                    boolean pressEnter = getBoolArg(args, "press_enter", false);
                    return sandbox.type(getAliasArg(args), text, pressEnter);
                });
            }
        };
    }

    public static BaseTool createKeyTool() {
        return new BaseTool(
                "computer_key",
                "Sends a keyboard key or shortcut combination (e.g., 'Return', 'Escape', 'Tab', 'BackSpace', 'ctrl+l', 'ctrl+c', 'alt+F4') to the VM or local desktop, then captures a screenshot."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "key", Schema.builder().type("STRING").description("Key or combo such as 'Return', 'Escape', 'Tab', 'ctrl+l', 'ctrl+shift+i', 'alt+F4'.").build(),
                                        "alias", Schema.builder().type("STRING").description("SSH session alias (default: 'default').").build()
                                ))
                                .required(ImmutableList.of("key"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String keyCombo = args.get("key") != null ? String.valueOf(args.get("key")) : "Return";
                    return sandbox.key(getAliasArg(args), keyCombo);
                });
            }
        };
    }

    public static BaseTool createScrollTool() {
        return new BaseTool(
                "computer_scroll",
                "Scrolls the mouse wheel at normalized coordinates (x, y) in [0..1000] in the specified direction ('up', 'down', 'left', 'right'), then captures a verification screenshot."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.<String, Schema>builder()
                                        .put("x", Schema.builder().type("INTEGER").description("Normalized X coordinate in [0..1000] (default: 500).").build())
                                        .put("y", Schema.builder().type("INTEGER").description("Normalized Y coordinate in [0..1000] (default: 500).").build())
                                        .put("direction", Schema.builder().type("STRING").description("Scroll direction: 'down' (default), 'up', 'left', 'right'.").build())
                                        .put("clicks", Schema.builder().type("INTEGER").description("Number of scroll wheel clicks (default: 4).").build())
                                        .put("alias", Schema.builder().type("STRING").description("SSH session alias (default: 'default').").build())
                                        .build())
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    int x = getIntArg(args, "x", 500);
                    int y = getIntArg(args, "y", 500);
                    String direction = args.get("direction") != null ? String.valueOf(args.get("direction")) : "down";
                    int clicks = getIntArg(args, "clicks", 4);
                    return sandbox.scroll(getAliasArg(args), x, y, direction, clicks);
                });
            }
        };
    }

    public static BaseTool createLaunchAppTool() {
        return new BaseTool(
                "computer_launch_app",
                "Launches an X11 GUI application (e.g., 'chromium-browser --no-sandbox https://example.com', 'firefox', 'xterm', 'xcalc') on the VM virtual display (:99) in the background and captures a screenshot."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "command", Schema.builder().type("STRING").description("Application command to launch on DISPLAY=:99.").build(),
                                        "alias", Schema.builder().type("STRING").description("SSH session alias (default: 'default').").build()
                                ))
                                .required(ImmutableList.of("command"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String cmd = args.get("command") != null ? String.valueOf(args.get("command")) : "";
                    return sandbox.launchApp(getAliasArg(args), cmd);
                });
            }
        };
    }

    public static List<BaseTool> createSuite() {
        return List.of(
                createInitVmTool(),
                createScreenshotTool(),
                createClickTool(),
                createMoveOrDragTool(),
                createTypeTool(),
                createKeyTool(),
                createScrollTool(),
                createLaunchAppTool()
        );
    }
}
