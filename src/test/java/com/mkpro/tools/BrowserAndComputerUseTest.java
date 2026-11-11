package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.genai.types.FunctionDeclaration;
import com.mkpro.agents.ToolRegistry;
import com.mkpro.infra.computer.ScreenGridAnnotator;
import com.mkpro.infra.computer.ScreenGridAnnotator.SomElement;
import com.mkpro.infra.computer.VmDesktopSandbox;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ScreenGridAnnotator, VmDesktopSandbox, ComputerUseTools, BrowserUseTools, and ToolRegistry.
 */
public class BrowserAndComputerUseTest {

    // =========================================================================
    // 1. ScreenGridAnnotator Tests
    // =========================================================================

    @Test
    void testCoordinateNormalizationAndClamping() {
        assertEquals(0, ScreenGridAnnotator.normToPixelX(0, 1280));
        assertEquals(640, ScreenGridAnnotator.normToPixelX(500, 1280));
        assertEquals(1280, ScreenGridAnnotator.normToPixelX(1000, 1280));

        assertEquals(0, ScreenGridAnnotator.normToPixelY(0, 800));
        assertEquals(400, ScreenGridAnnotator.normToPixelY(500, 800));
        assertEquals(800, ScreenGridAnnotator.normToPixelY(1000, 800));

        // Clamping out-of-range inputs
        assertEquals(0, ScreenGridAnnotator.normToPixelX(-150, 1280));
        assertEquals(1280, ScreenGridAnnotator.normToPixelX(1500, 1280));
        assertEquals(0, ScreenGridAnnotator.normToPixelY(-50, 800));
        assertEquals(800, ScreenGridAnnotator.normToPixelY(2000, 800));

        // Inverse pixel-to-normalized conversion
        assertEquals(500, ScreenGridAnnotator.pixelToNormX(640, 1280));
        assertEquals(500, ScreenGridAnnotator.pixelToNormY(400, 800));
        assertEquals(0, ScreenGridAnnotator.pixelToNormX(-10, 1280));
        assertEquals(1000, ScreenGridAnnotator.pixelToNormX(1400, 1280));
    }

    @Test
    void testAnnotateWithGridAndCrosshair() {
        BufferedImage raw = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = raw.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, 400, 300);
        g.dispose();

        BufferedImage annotated = ScreenGridAnnotator.annotateWithGridAndCrosshair(
                raw, 500, 500, "click(500,500)", true
        );
        assertNotNull(annotated);
        assertEquals(400, annotated.getWidth());
        assertEquals(300, annotated.getHeight());

        // Center pixel (200, 150) should be modified by the red target crosshair
        int centerRgb = annotated.getRGB(200, 150);
        assertNotEquals(raw.getRGB(200, 150), centerRgb);
        Color centerColor = new Color(centerRgb, true);
        assertTrue(centerColor.getRed() > 200, "Target crosshair center should have high red component");
    }

    @Test
    void testAnnotateWithSomBadges() {
        BufferedImage raw = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        List<SomElement> elements = List.of(
                new SomElement(1, "button", "button", "Submit", 50, 60, 100, 32),
                new SomElement(2, "a", "link", "Learn More", 200, 120, 120, 24)
        );

        BufferedImage annotated = ScreenGridAnnotator.annotateWithSomBadges(raw, elements);
        assertNotNull(annotated);
        assertEquals(400, annotated.getWidth());
        assertEquals(300, annotated.getHeight());

        // Bounding box border at (50, 60) should be colored
        assertNotEquals(raw.getRGB(50, 60), annotated.getRGB(50, 60));
    }

    // =========================================================================
    // 2. VmDesktopSandbox Command Builder & State Tests
    // =========================================================================

    @Test
    void testBuildClickCommand() {
        String singleClick = VmDesktopSandbox.buildClickCommand(":99", 640, 400, 1, false);
        assertTrue(singleClick.contains(".local/mkpro_gui/usr/bin"));
        assertTrue(singleClick.endsWith("DISPLAY=:99 xdotool mousemove --sync 640 400 click 1"));

        String doubleClick = VmDesktopSandbox.buildClickCommand(":99", 320, 200, 1, true);
        assertTrue(doubleClick.endsWith("DISPLAY=:99 xdotool mousemove --sync 320 200 click --repeat 2 --delay 80 1"));

        String rightClick = VmDesktopSandbox.buildClickCommand(":99", 100, 150, 3, false);
        assertTrue(rightClick.endsWith("DISPLAY=:99 xdotool mousemove --sync 100 150 click 3"));

        // Sanitize invalid display injection attempt
        String sanitized = VmDesktopSandbox.buildClickCommand(":99; rm -rf /", 10, 20, 1, false);
        assertTrue(sanitized.contains("DISPLAY=:99 "));
        assertFalse(sanitized.contains("rm -rf"));
    }

    @Test
    void testBuildMoveOrDragCommand() {
        String moveCmd = VmDesktopSandbox.buildMoveOrDragCommand(":99", 100, 100, 500, 600, false);
        assertTrue(moveCmd.endsWith("DISPLAY=:99 xdotool mousemove --sync 500 600"));

        String dragCmd = VmDesktopSandbox.buildMoveOrDragCommand(":99", 100, 100, 500, 600, true);
        assertTrue(dragCmd.contains("mousemove --sync 100 100 mousedown 1"));
        assertTrue(dragCmd.contains("mousemove --sync 500 600"));
        assertTrue(dragCmd.contains("mouseup 1"));
    }

    @Test
    void testBuildTypeCommand() {
        String text = "https://example.com/search?q=mkpro&lang=en";
        String expectedB64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));

        String cmdWithEnter = VmDesktopSandbox.buildTypeCommand(":99", text, true);
        assertTrue(cmdWithEnter.contains(".local/mkpro_gui/usr/bin"));
        assertTrue(cmdWithEnter.contains(expectedB64));
        assertTrue(cmdWithEnter.contains("xclip -selection clipboard"));
        assertTrue(cmdWithEnter.contains("xdotool key Return"));

        String cmdNoEnter = VmDesktopSandbox.buildTypeCommand(":99", text, false);
        assertFalse(cmdNoEnter.contains("xdotool key Return"));
    }

    @Test
    void testBuildKeyCommand() {
        String enterCmd = VmDesktopSandbox.buildKeyCommand(":99", "enter");
        assertTrue(enterCmd.endsWith("DISPLAY=:99 xdotool key --clearmodifiers 'Return'"));

        String shortcutCmd = VmDesktopSandbox.buildKeyCommand(":99", "ctrl+l");
        assertTrue(shortcutCmd.endsWith("DISPLAY=:99 xdotool key --clearmodifiers 'ctrl+l'"));

        String altF4Cmd = VmDesktopSandbox.buildKeyCommand(":99", "alt+F4");
        assertTrue(altF4Cmd.endsWith("DISPLAY=:99 xdotool key --clearmodifiers 'alt+F4'"));
    }

    @Test
    void testBuildScrollCommand() {
        String scrollDown = VmDesktopSandbox.buildScrollCommand(":99", 640, 400, "down", 5);
        assertTrue(scrollDown.endsWith("DISPLAY=:99 xdotool mousemove --sync 640 400 click --repeat 5 --delay 40 5"));

        String scrollUp = VmDesktopSandbox.buildScrollCommand(":99", 640, 400, "up", 3);
        assertTrue(scrollUp.endsWith("DISPLAY=:99 xdotool mousemove --sync 640 400 click --repeat 3 --delay 40 4"));
    }

    @Test
    void testVmDesktopSandboxStatusAndModeSwitching() {
        VmDesktopSandbox sandbox = VmDesktopSandbox.getInstance();
        String originalMode = sandbox.getMode();
        try {
            sandbox.setMode("local");
            assertEquals("local", sandbox.getMode());

            sandbox.setMode("vm");
            assertEquals("vm", sandbox.getMode());

            sandbox.setLastFrame("dGVzdA==", "/tmp/test.png", "unit_test_action");
            Map<String, Object> status = sandbox.getStatus("default");
            assertNotNull(status);
            assertEquals("vm", status.get("mode"));
            assertEquals(":99", status.get("display"));
            assertEquals("unit_test_action", status.get("lastAction"));
            assertEquals(Boolean.TRUE, status.get("hasFrame"));
        } finally {
            sandbox.setMode(originalMode);
        }
    }

    // =========================================================================
    // 3. ToolRegistry & Tool Suites Resolution Tests
    // =========================================================================

    @Test
    void testToolRegistryResolvesComputerAndBrowserSuites() {
        ToolRegistry registry = new ToolRegistry(null, null);

        List<BaseTool> computerTools = registry.resolve(List.of("computer_use"));
        assertEquals(8, computerTools.size());
        Set<String> computerNames = computerTools.stream().map(BaseTool::name).collect(Collectors.toSet());
        assertTrue(computerNames.containsAll(Set.of(
                "computer_init_vm",
                "computer_screenshot",
                "computer_click",
                "computer_move_or_drag",
                "computer_type",
                "computer_key",
                "computer_scroll",
                "computer_launch_app"
        )));

        for (BaseTool tool : computerTools) {
            Optional<FunctionDeclaration> decl = tool.declaration();
            assertTrue(decl.isPresent(), "Declaration missing for " + tool.name());
            assertNotNull(decl.get().parameters(), "Parameters missing for " + tool.name());
        }

        List<BaseTool> browserTools = registry.resolve(List.of("browser_use"));
        assertEquals(5, browserTools.size());
        Set<String> browserNames = browserTools.stream().map(BaseTool::name).collect(Collectors.toSet());
        assertTrue(browserNames.containsAll(Set.of(
                "browser_open",
                "browser_snapshot",
                "browser_act",
                "browser_extract_markdown",
                "browser_close"
        )));

        for (BaseTool tool : browserTools) {
            Optional<FunctionDeclaration> decl = tool.declaration();
            assertTrue(decl.isPresent(), "Declaration missing for " + tool.name());
            assertNotNull(decl.get().parameters(), "Parameters missing for " + tool.name());
        }
    }

    @Test
    void testBrowserHtmlToCleanMarkdown() {
        String html = "<html><head><title>Test Page</title><script>alert(1)</script></head>" +
                "<body><h1>Welcome</h1><p>Hello world</p><a href='https://example.com'>Example Link</a></body></html>";
        String md = BrowserUseTools.htmlToCleanMarkdown(html);
        assertTrue(md.contains("# Test Page"));
        assertTrue(md.contains("# Welcome"));
        assertTrue(md.contains("Hello world"));
        assertTrue(md.contains("[Example Link](https://example.com)"));
        assertFalse(md.contains("alert(1)"));
    }
}
