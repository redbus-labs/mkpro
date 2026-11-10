package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.mkpro.agents.AgentManager;
import com.mkpro.events.MkProEvent;
import com.mkpro.events.MkProEventBus;
import com.mkpro.infra.computer.ScreenGridAnnotator;
import com.mkpro.infra.computer.ScreenGridAnnotator.SomElement;
import com.mkpro.infra.computer.VmDesktopSandbox;
import com.mkpro.infra.ssh.SshSessionManager;
import com.mkpro.utils.PathUtils;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.reactivex.rxjava3.core.Single;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BrowserUseTools provides Set-of-Mark (SoM) visual browser automation:
 * - browser_open: Opens a URL in the browser (local Selenium Chrome or remote VM browser on DISPLAY=:99) and returns a Set-of-Mark snapshot.
 * - browser_snapshot: Injects SoM JS to discover visible interactive elements, draws [1..N] badges onto the screenshot, and returns the interactive element catalog.
 * - browser_act: Performs click, type, select, scroll, or hover by SoM element_id or normalized (x, y) coordinates.
 * - browser_extract_markdown: Extracts clean, structured Markdown from the current page DOM.
 * - browser_close: Closes the browser session.
 */
public class BrowserUseTools {

    private static final AtomicReference<WebDriver> driverRef = new AtomicReference<>();
    private static final Map<Integer, SomElement> lastSomElements = new ConcurrentHashMap<>();
    private static volatile String lastVmUrl = "";

    /**
     * JavaScript snippet injected into the page to locate all visible interactive elements,
     * tag them with data-mkpro-som-id="1..N", and return their bounding boxes and accessible labels.
     */
    public static final String SOM_INJECTION_JS =
            "(() => {\n" +
            "  const selectors = 'a[href], button, input, select, textarea, [role=\"button\"], [role=\"link\"], [role=\"checkbox\"], [role=\"tab\"], [onclick], summary';\n" +
            "  const nodes = Array.from(document.querySelectorAll(selectors));\n" +
            "  const results = [];\n" +
            "  let id = 1;\n" +
            "  for (const el of nodes) {\n" +
            "    if (id > 80) break;\n" +
            "    const rect = el.getBoundingClientRect();\n" +
            "    if (rect.width < 6 || rect.height < 6) continue;\n" +
            "    if (rect.bottom < 0 || rect.right < 0 || rect.top > window.innerHeight || rect.left > window.innerWidth) continue;\n" +
            "    const style = window.getComputedStyle(el);\n" +
            "    if (style.visibility === 'hidden' || style.display === 'none' || parseFloat(style.opacity || '1') < 0.05) continue;\n" +
            "    el.setAttribute('data-mkpro-som-id', String(id));\n" +
            "    const tag = (el.tagName || '').toLowerCase();\n" +
            "    const role = el.getAttribute('role') || el.getAttribute('type') || tag;\n" +
            "    let label = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('name') || '').trim();\n" +
            "    label = label.replace(/\\s+/g, ' ').slice(0, 80);\n" +
            "    results.push({\n" +
            "      id: id,\n" +
            "      tag: tag,\n" +
            "      role: String(role),\n" +
            "      label: label,\n" +
            "      x: Math.round(rect.left),\n" +
            "      y: Math.round(rect.top),\n" +
            "      width: Math.round(rect.width),\n" +
            "      height: Math.round(rect.height)\n" +
            "    });\n" +
            "    id++;\n" +
            "  }\n" +
            "  return results;\n" +
            "})();";

    private static synchronized WebDriver getOrCreateLocalDriver() {
        WebDriver existing = driverRef.get();
        if (existing != null) {
            try {
                existing.getTitle();
                return existing;
            } catch (Exception e) {
                try { existing.quit(); } catch (Exception ignored) {}
                driverRef.set(null);
            }
        }
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        boolean visible = "true".equalsIgnoreCase(System.getProperty("mkpro.browser.visible"));
        if (!visible) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--window-size=1280,800");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        WebDriver created = new ChromeDriver(options);
        driverRef.set(created);
        return created;
    }

    public static synchronized void closeDriver() {
        WebDriver driver = driverRef.getAndSet(null);
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception ignored) {}
        }
        lastSomElements.clear();
    }

    private static boolean useRemoteVmBrowser(Map<String, Object> args) {
        Object target = args.get("target");
        if (target != null && "local".equalsIgnoreCase(String.valueOf(target).trim())) {
            return false;
        }
        if (target != null && "vm".equalsIgnoreCase(String.valueOf(target).trim())) {
            return SshSessionManager.getInstance().hasActiveSessions();
        }
        // If local Selenium can run, or if VM mode is explicitly active with Xvfb running
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> takeSomSnapshotInternal(WebDriver driver, String actionNote) throws Exception {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object rawList = js.executeScript(SOM_INJECTION_JS);
        List<SomElement> somElements = new ArrayList<>();
        lastSomElements.clear();

        if (rawList instanceof List<?>) {
            for (Object item : (List<?>) rawList) {
                if (item instanceof Map<?, ?> m) {
                    int id = ((Number) m.get("id")).intValue();
                    String tag = m.get("tag") != null ? String.valueOf(m.get("tag")) : "";
                    String role = m.get("role") != null ? String.valueOf(m.get("role")) : "";
                    String label = m.get("label") != null ? String.valueOf(m.get("label")) : "";
                    int x = ((Number) m.get("x")).intValue();
                    int y = ((Number) m.get("y")).intValue();
                    int w = ((Number) m.get("width")).intValue();
                    int h = ((Number) m.get("height")).intValue();
                    SomElement som = new SomElement(id, tag, role, label, x, y, w, h);
                    somElements.add(som);
                    lastSomElements.put(id, som);
                }
            }
        }

        byte[] rawPng = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        BufferedImage srcImg = ImageIO.read(new ByteArrayInputStream(rawPng));
        BufferedImage annotated = ScreenGridAnnotator.annotateWithSomBadges(srcImg, somElements);

        Path capturesDir = PathUtils.getMkproDataDir().resolve("captures");
        PathUtils.ensureDirectory(capturesDir);
        long ts = Instant.now().toEpochMilli();
        Path outPath = capturesDir.resolve("browser_som_" + ts + ".png");
        ImageIO.write(annotated, "png", outPath.toFile());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(annotated, "png", baos);
        byte[] annotatedBytes = baos.toByteArray();
        String b64 = Base64.getEncoder().encodeToString(annotatedBytes);

        VmDesktopSandbox.getInstance().setLastFrame(
                b64,
                outPath.toAbsolutePath().toString(),
                actionNote != null ? actionNote : "browser_snapshot"
        );

        if (AgentManager.currentTurnMediaParts != null) {
            Part imagePart = Part.builder()
                    .inlineData(Blob.builder()
                            .mimeType("image/png")
                            .data(annotatedBytes)
                            .build())
                    .build();
            AgentManager.currentTurnMediaParts.add(imagePart);
        }

        StringBuilder catalog = new StringBuilder();
        for (SomElement el : somElements) {
            int cx = el.x() + el.width() / 2;
            int cy = el.y() + el.height() / 2;
            int normX = ScreenGridAnnotator.pixelToNormX(cx, srcImg.getWidth());
            int normY = ScreenGridAnnotator.pixelToNormY(cy, srcImg.getHeight());
            catalog.append(String.format("[%d] <%s role=\"%s\"> \"%s\" (norm=%d,%d)\n",
                    el.id(), el.tag(), el.role(), el.label(), normX, normY));
        }

        if (MkProEventBus.INSTANCE != null) {
            MkProEventBus.INSTANCE.emit(MkProEvent.system(
                    "[BrowserUse] SoM Snapshot (" + somElements.size() + " interactive elements) -> " + outPath.getFileName()
            ));
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "ok");
        res.put("url", driver.getCurrentUrl());
        res.put("title", driver.getTitle());
        res.put("element_count", somElements.size());
        res.put("interactive_elements", catalog.toString().trim());
        res.put("screenshot_path", outPath.toAbsolutePath().toString());
        return res;
    }

    public static BaseTool createOpenTool() {
        return new BaseTool(
                "browser_open",
                "Opens a URL in the browser, injects Set-of-Mark (SoM) interactive badges [1..N], captures an annotated screenshot for Vision analysis, and returns the interactive element list."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "url", Schema.builder().type("STRING").description("The URL to navigate to (e.g., 'https://example.com').").build(),
                                        "target", Schema.builder().type("STRING").description("Optional execution target: 'local' (Selenium headless Chrome) or 'vm' (remote Xvfb GUI browser).").build()
                                ))
                                .required(ImmutableList.of("url"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String url = String.valueOf(args.get("url")).trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
                        url = "https://" + url;
                    }
                    lastVmUrl = url;

                    if (useRemoteVmBrowser(args)) {
                        String cmd = String.format(
                                "(chromium-browser --no-sandbox --window-size=1280,800 '%s' || google-chrome --no-sandbox --window-size=1280,800 '%s' || firefox '%s')",
                                url.replace("'", ""), url.replace("'", ""), url.replace("'", "")
                        );
                        return VmDesktopSandbox.getInstance().launchApp("default", cmd);
                    }

                    try {
                        WebDriver driver = getOrCreateLocalDriver();
                        driver.get(url);
                        Thread.sleep(600);
                        return takeSomSnapshotInternal(driver, "browser_open(" + url + ")");
                    } catch (Exception e) {
                        // Fallback to VM browser if local ChromeDriver is unavailable and SSH is connected
                        if (SshSessionManager.getInstance().hasActiveSessions()) {
                            String cmd = String.format(
                                    "(chromium-browser --no-sandbox --window-size=1280,800 '%s' || firefox '%s')",
                                    url.replace("'", ""), url.replace("'", "")
                            );
                            return VmDesktopSandbox.getInstance().launchApp("default", cmd);
                        }
                        return Map.of("status", "error", "error", "browser_open failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createSnapshotTool() {
        return new BaseTool(
                "browser_snapshot",
                "Refreshes the Set-of-Mark (SoM) visual snapshot of the current browser page, drawing numbered [1..N] badges on all visible interactive elements and returning their IDs and labels."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Collections.emptyMap())
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    try {
                        if (driverRef.get() != null) {
                            return takeSomSnapshotInternal(driverRef.get(), "browser_snapshot");
                        }
                        if (SshSessionManager.getInstance().hasActiveSessions()) {
                            return VmDesktopSandbox.getInstance().captureScreenshot("default", null, null, "browser_snapshot", true);
                        }
                        WebDriver driver = getOrCreateLocalDriver();
                        return takeSomSnapshotInternal(driver, "browser_snapshot");
                    } catch (Exception e) {
                        return Map.of("status", "error", "error", "browser_snapshot failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createActTool() {
        return new BaseTool(
                "browser_act",
                "Interacts with an element on the current page by its Set-of-Mark element_id (from browser_snapshot) or normalized (x, y) coordinates. Actions: 'click', 'type', 'press_enter', 'scroll_down', 'scroll_up', 'back', 'hover'."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.<String, Schema>builder()
                                        .put("action", Schema.builder().type("STRING").description("Action to perform: 'click', 'type', 'press_enter', 'scroll_down', 'scroll_up', 'back', 'hover'.").build())
                                        .put("element_id", Schema.builder().type("INTEGER").description("Set-of-Mark badge ID [1..N] of the target interactive element.").build())
                                        .put("text", Schema.builder().type("STRING").description("Text to type when action is 'type'.").build())
                                        .put("press_enter", Schema.builder().type("BOOLEAN").description("If true during 'type', presses Enter after typing.").build())
                                        .put("x", Schema.builder().type("INTEGER").description("Optional fallback normalized X coordinate [0..1000] if element_id is omitted.").build())
                                        .put("y", Schema.builder().type("INTEGER").description("Optional fallback normalized Y coordinate [0..1000] if element_id is omitted.").build())
                                        .build())
                                .required(ImmutableList.of("action"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String action = String.valueOf(args.getOrDefault("action", "click")).trim().toLowerCase(Locale.ROOT);
                    Integer elementId = null;
                    if (args.get("element_id") instanceof Number n) {
                        elementId = n.intValue();
                    } else if (args.get("element_id") instanceof String s && !s.isBlank()) {
                        try { elementId = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
                    }

                    String text = args.get("text") != null ? String.valueOf(args.get("text")) : "";
                    boolean pressEnter = Boolean.TRUE.equals(args.get("press_enter"))
                            || "true".equalsIgnoreCase(String.valueOf(args.get("press_enter")));

                    try {
                        WebDriver driver = driverRef.get();
                        if (driver == null) {
                            // If no Selenium session is active, delegate coordinate actions to VmDesktopSandbox
                            if (args.get("x") instanceof Number nx && args.get("y") instanceof Number ny) {
                                if ("type".equals(action)) {
                                    VmDesktopSandbox.getInstance().click("default", nx.intValue(), ny.intValue(), 1, false);
                                    return VmDesktopSandbox.getInstance().type("default", text, pressEnter);
                                }
                                return VmDesktopSandbox.getInstance().click("default", nx.intValue(), ny.intValue(), 1, false);
                            }
                            return Map.of("status", "error", "error", "No active browser session. Call browser_open first.");
                        }

                        JavascriptExecutor js = (JavascriptExecutor) driver;
                        if ("scroll_down".equals(action)) {
                            js.executeScript("window.scrollBy(0, 500);");
                        } else if ("scroll_up".equals(action)) {
                            js.executeScript("window.scrollBy(0, -500);");
                        } else if ("back".equals(action)) {
                            driver.navigate().back();
                        } else if (elementId != null) {
                            By selector = By.cssSelector("[data-mkpro-som-id='" + elementId + "']");
                            WebElement el = driver.findElement(selector);
                            js.executeScript("arguments[0].scrollIntoView({block: 'center', inline: 'center'});", el);
                            Thread.sleep(120);
                            if ("click".equals(action)) {
                                try {
                                    el.click();
                                } catch (Exception clickEx) {
                                    js.executeScript("arguments[0].click();", el);
                                }
                            } else if ("type".equals(action)) {
                                try { el.clear(); } catch (Exception ignored) {}
                                el.sendKeys(text);
                                if (pressEnter) {
                                    el.sendKeys(Keys.ENTER);
                                }
                            } else if ("press_enter".equals(action)) {
                                el.sendKeys(Keys.ENTER);
                            } else if ("hover".equals(action)) {
                                js.executeScript(
                                        "arguments[0].dispatchEvent(new MouseEvent('mouseover', {bubbles: true}));",
                                        el
                                );
                            }
                        } else if (args.get("x") instanceof Number nx && args.get("y") instanceof Number ny) {
                            Dimension win = driver.manage().window().getSize();
                            int px = ScreenGridAnnotator.normToPixelX(nx.intValue(), win.getWidth());
                            int py = ScreenGridAnnotator.normToPixelY(ny.intValue(), win.getHeight());
                            js.executeScript(
                                    "const el = document.elementFromPoint(arguments[0], arguments[1]); if (el) { el.focus(); el.click(); }",
                                    px, py
                            );
                        } else {
                            return Map.of("status", "error", "error", "Provide element_id or (x, y) for browser_act.");
                        }

                        Thread.sleep(500);
                        return takeSomSnapshotInternal(driver, "browser_act(" + action + (elementId != null ? " #" + elementId : "") + ")");
                    } catch (Exception e) {
                        return Map.of("status", "error", "error", "browser_act failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createExtractMarkdownTool() {
        return new BaseTool(
                "browser_extract_markdown",
                "Extracts clean, readable Markdown from the current browser page DOM (preserving headings, links, lists, tables, and code blocks while stripping scripts/styles)."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Collections.emptyMap())
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    try {
                        WebDriver driver = driverRef.get();
                        if (driver == null) {
                            if (lastVmUrl != null && !lastVmUrl.isBlank()) {
                                HttpClient client = HttpClient.newBuilder()
                                        .connectTimeout(Duration.ofSeconds(15))
                                        .followRedirects(HttpClient.Redirect.NORMAL)
                                        .build();
                                HttpRequest req = HttpRequest.newBuilder()
                                        .uri(URI.create(lastVmUrl))
                                        .timeout(Duration.ofSeconds(15))
                                        .GET()
                                        .build();
                                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                                String html = resp.body();
                                String title = extractHtmlTitle(html);
                                return Map.of(
                                        "status", "ok",
                                        "url", lastVmUrl,
                                        "title", title,
                                        "markdown", htmlToCleanMarkdown(html)
                                );
                            }
                            return Map.of("status", "error", "error", "No active browser session. Call browser_open first.");
                        }
                        String html = driver.getPageSource();
                        String md = htmlToCleanMarkdown(html);
                        return Map.of(
                                "status", "ok",
                                "url", driver.getCurrentUrl(),
                                "title", driver.getTitle(),
                                "markdown", md
                        );
                    } catch (Exception e) {
                        return Map.of("status", "error", "error", "Markdown extraction failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    private static String extractHtmlTitle(String html) {
        if (html == null || html.isEmpty()) return "";
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (m.find()) {
            return decodeBasicEntities(m.group(1).replaceAll("<[^>]+>", "").trim());
        }
        return "";
    }

    public static String htmlToCleanMarkdown(String html) {
        if (html == null || html.isBlank()) return "";
        String title = extractHtmlTitle(html);

        String cleaned = html;
        cleaned = cleaned.replaceAll("(?is)<!--.*?-->", "");
        cleaned = cleaned.replaceAll("(?is)<(script|style|noscript|svg|iframe|head)[^>]*>.*?</\\1>", "");

        // Convert links <a href="...">text</a> -> [text](href)
        Matcher linkMatcher = Pattern.compile("(?is)<a\\s+[^>]*?href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>").matcher(cleaned);
        StringBuilder linkBuf = new StringBuilder();
        while (linkMatcher.find()) {
            String href = linkMatcher.group(1).trim();
            String linkText = linkMatcher.group(2).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
            String replacement = linkText.isEmpty() ? href : "[" + linkText + "](" + href + ")";
            linkMatcher.appendReplacement(linkBuf, Matcher.quoteReplacement(replacement));
        }
        linkMatcher.appendTail(linkBuf);
        cleaned = linkBuf.toString();

        // Convert headings <h1>..<h6>
        for (int level = 1; level <= 6; level++) {
            String hashes = "#".repeat(level);
            cleaned = cleaned.replaceAll("(?is)<h" + level + "[^>]*>(.*?)</h" + level + ">", "\n" + hashes + " $1\n");
        }

        // Convert list items, pre, paragraphs, line breaks
        cleaned = cleaned.replaceAll("(?is)<li[^>]*>(.*?)</li>", "\n- $1\n");
        cleaned = cleaned.replaceAll("(?is)<pre[^>]*>(.*?)</pre>", "\n```\n$1\n```\n");
        cleaned = cleaned.replaceAll("(?is)<p[^>]*>(.*?)</p>", "\n$1\n\n");
        cleaned = cleaned.replaceAll("(?is)<br\\s*/?>", "\n");
        cleaned = cleaned.replaceAll("(?is)</(div|section|article|header|footer|tr|table|ul|ol)>", "\n");

        // Strip remaining tags
        cleaned = cleaned.replaceAll("(?is)<[^>]+>", "");

        // Decode basic HTML entities
        cleaned = decodeBasicEntities(cleaned);

        // Normalize whitespace and blank lines
        cleaned = cleaned.replaceAll("[ \\t]+\\n", "\n");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();

        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) {
            sb.append("# ").append(title).append("\n\n");
        }
        sb.append(cleaned);

        String result = sb.toString().trim();
        if (result.length() > 16000) {
            return result.substring(0, 16000) + "\n...[truncated]";
        }
        return result;
    }

    private static String decodeBasicEntities(String text) {
        if (text == null || text.isEmpty()) return "";
        return text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    public static BaseTool createCloseTool() {
        return new BaseTool(
                "browser_close",
                "Closes the active Browser Use session and releases resources."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Collections.emptyMap())
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    closeDriver();
                    return Map.of("status", "ok", "message", "Browser session closed.");
                });
            }
        };
    }

    public static List<BaseTool> createSuite() {
        return List.of(
                createOpenTool(),
                createSnapshotTool(),
                createActTool(),
                createExtractMarkdownTool(),
                createCloseTool()
        );
    }
}
