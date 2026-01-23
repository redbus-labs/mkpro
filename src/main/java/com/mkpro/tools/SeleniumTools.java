package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.reactivex.rxjava3.core.Single;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class SeleniumTools {

    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BLUE = "\u001b[34m";

    private static final AtomicReference<WebDriver> driverRef = new AtomicReference<>();

    private static synchronized WebDriver getDriver() {
        if (driverRef.get() == null) {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            
            boolean visible = "true".equalsIgnoreCase(System.getProperty("mkpro.browser.visible"));
            if (!visible) {
                options.addArguments("--headless=new"); // Default to headless
            }
            
            options.addArguments("--remote-allow-origins=*");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--disable-gpu");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            driverRef.set(new ChromeDriver(options));
        }
        return driverRef.get();
    }

    private static synchronized void quitDriver() {
        WebDriver driver = driverRef.get();
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception e) {
                // Ignore
            }
            driverRef.set(null);
        }
    }

    public static BaseTool createNavigateTool() {
        return new BaseTool(
                "selenium_navigate",
                "Navigates to a URL using a real browser (Chrome Headless) and returns the visible text content."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "url", Schema.builder().type("STRING").description("The URL to visit.").build()
                                ))
                                .required(ImmutableList.of("url"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String url = (String) args.get("url");
                System.out.println(ANSI_BLUE + "[WebSurfer] Navigating to: " + url + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        WebDriver driver = getDriver();
                        driver.get(url);
                        String title = driver.getTitle();
                        String bodyText = driver.findElement(By.tagName("body")).getText();
                        
                        // Truncate if too large
                        if (bodyText.length() > 10000) {
                            bodyText = bodyText.substring(0, 10000) + "\n...[truncated]";
                        }

                        return ImmutableMap.of(
                            "title", title,
                            "content", bodyText,
                            "current_url", driver.getCurrentUrl()
                        );
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Navigation failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createClickTool() {
        return new BaseTool(
                "selenium_click",
                "Clicks an element on the current page identified by a CSS selector."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "selector", Schema.builder().type("STRING").description("CSS selector for the element to click.").build()
                                ))
                                .required(ImmutableList.of("selector"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String selector = (String) args.get("selector");
                System.out.println(ANSI_BLUE + "[WebSurfer] Clicking: " + selector + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        WebDriver driver = getDriver();
                        WebElement element = driver.findElement(By.cssSelector(selector));
                        element.click();
                        Thread.sleep(1000); // Wait for potential navigation/update
                        return Collections.singletonMap("status", "Clicked element: " + selector);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Click failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createTypeTool() {
        return new BaseTool(
                "selenium_type",
                "Types text into an input element identified by a CSS selector."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "selector", Schema.builder().type("STRING").description("CSS selector for the input element.").build(),
                                        "text", Schema.builder().type("STRING").description("The text to type.").build()
                                ))
                                .required(ImmutableList.of("selector", "text"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String selector = (String) args.get("selector");
                String text = (String) args.get("text");
                System.out.println(ANSI_BLUE + "[WebSurfer] Typing into " + selector + ": " + text + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        WebDriver driver = getDriver();
                        WebElement element = driver.findElement(By.cssSelector(selector));
                        element.sendKeys(text);
                        return Collections.singletonMap("status", "Typed text into: " + selector);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Typing failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createScreenshotTool() {
        return new BaseTool(
                "selenium_screenshot",
                "Takes a screenshot of the current page and saves it to a file."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "file_path", Schema.builder().type("STRING").description("Path to save the screenshot (e.g., screenshot.png).").build()
                                ))
                                .required(ImmutableList.of("file_path"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String filePath = (String) args.get("file_path");
                System.out.println(ANSI_BLUE + "[WebSurfer] Taking screenshot: " + filePath + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        WebDriver driver = getDriver();
                        File scrFile = ((TakesScreenshot)driver).getScreenshotAs(OutputType.FILE);
                        File destFile = new File(filePath);
                        Files.copy(scrFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        return Collections.singletonMap("status", "Screenshot saved to: " + destFile.getAbsolutePath());
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Screenshot failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createGetHtmlTool() {
        return new BaseTool(
                "selenium_get_html",
                "Returns the full HTML source of the current page."
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
                System.out.println(ANSI_BLUE + "[WebSurfer] Getting HTML source..." + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        WebDriver driver = getDriver();
                        String pageSource = driver.getPageSource();
                        if (pageSource.length() > 20000) {
                            pageSource = pageSource.substring(0, 20000) + "\n...[truncated]";
                        }
                        return Collections.singletonMap("html", pageSource);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Failed to get HTML: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createCloseTool() {
        return new BaseTool(
                "selenium_close",
                "Closes the browser session."
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
                System.out.println(ANSI_BLUE + "[WebSurfer] Closing browser..." + ANSI_RESET);
                return Single.fromCallable(() -> {
                    quitDriver();
                    return Collections.singletonMap("status", "Browser closed.");
                });
            }
        };
    }
}