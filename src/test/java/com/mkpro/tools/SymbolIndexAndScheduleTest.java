package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.mkpro.infra.mq.JobScheduleManager;
import com.mkpro.infra.mq.JobScheduleManager.ScheduledJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SymbolIndexTool (find_symbol, find_usages, incremental cache invalidation)
 * and JobScheduleManager (scheduled and event-triggered background jobs).
 */
public class SymbolIndexAndScheduleTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        SymbolIndexTool.setRootOverride(tempDir);
        JobScheduleManager.getInstance().clearSchedules();
    }

    @AfterEach
    void tearDown() {
        SymbolIndexTool.setRootOverride(null);
        SymbolIndexTool.clearCache();
        JobScheduleManager.getInstance().clearSchedules();
    }

    @Test
    @SuppressWarnings("unchecked")
    void symbolIndexFindsJavaAndPythonSymbolsAndUsagesAndInvalidatesOnEdit() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);

        Path javaFile = srcDir.resolve("OrderService.java");
        Files.writeString(javaFile, """
            package sample;

            public class OrderService {
                public String calculateInvoice(int amount) {
                    return "INV-" + amount;
                }
            }
            """);

        Path callerJavaFile = srcDir.resolve("OrderController.java");
        Files.writeString(callerJavaFile, """
            package sample;

            public class OrderController {
                private final OrderService service = new OrderService();

                public String handleRequest() {
                    return service.calculateInvoice(42);
                }
            }
            """);

        Path pyFile = srcDir.resolve("analytics.py");
        Files.writeString(pyFile, """
            class MetricsCollector:
                pass

            def compute_daily_summary(items):
                return len(items)
            """);

        BaseTool findSymbol = SymbolIndexTool.createFindSymbolTool();
        BaseTool findUsages = SymbolIndexTool.createFindUsagesTool();

        // 1. Find Java class OrderService
        Map<String, Object> classResp = findSymbol.runAsync(Map.of("query", "OrderService", "kind", "CLASS"), null).blockingGet();
        assertEquals(3, ((Number) classResp.get("indexedFiles")).intValue());
        List<Map<String, Object>> classSymbols = (List<Map<String, Object>>) classResp.get("symbols");
        assertEquals(1, classSymbols.size());
        assertEquals("OrderService", classSymbols.get(0).get("name"));
        assertEquals("CLASS", classSymbols.get(0).get("kind"));

        // 2. Find Java method calculateInvoice
        Map<String, Object> methodResp = findSymbol.runAsync(Map.of("query", "calculateInvoice"), null).blockingGet();
        List<Map<String, Object>> methodSymbols = (List<Map<String, Object>>) methodResp.get("symbols");
        assertEquals(1, methodSymbols.size());
        assertEquals("calculateInvoice", methodSymbols.get(0).get("name"));
        assertEquals("METHOD", methodSymbols.get(0).get("kind"));

        // 3. Find Python class and function
        Map<String, Object> pyClassResp = findSymbol.runAsync(Map.of("query", "MetricsCollector"), null).blockingGet();
        List<Map<String, Object>> pyClasses = (List<Map<String, Object>>) pyClassResp.get("symbols");
        assertEquals(1, pyClasses.size());
        assertEquals("CLASS", pyClasses.get(0).get("kind"));

        Map<String, Object> pyFuncResp = findSymbol.runAsync(Map.of("query", "compute_daily_summary"), null).blockingGet();
        List<Map<String, Object>> pyFuncs = (List<Map<String, Object>>) pyFuncResp.get("symbols");
        assertEquals(1, pyFuncs.size());
        assertEquals("FUNCTION", pyFuncs.get(0).get("kind"));

        // 4. Find usages of calculateInvoice across files
        Map<String, Object> usageResp = findUsages.runAsync(Map.of("symbol", "calculateInvoice"), null).blockingGet();
        assertEquals(1, ((Number) usageResp.get("definitionCount")).intValue());
        assertEquals(1, ((Number) usageResp.get("usageCount")).intValue());
        List<Map<String, Object>> usages = (List<Map<String, Object>>) usageResp.get("usages");
        assertTrue(usages.get(0).get("path").toString().endsWith("OrderController.java"));
        assertTrue(usages.get(0).get("snippet").toString().contains("service.calculateInvoice(42)"));

        // 5. Edit OrderService.java to add refundOrder method and call SymbolIndexTool.invalidate(javaFile)
        Files.writeString(javaFile, """
            package sample;

            public class OrderService {
                public String calculateInvoice(int amount) {
                    return "INV-" + amount;
                }

                public boolean refundOrder(String orderId) {
                    return orderId != null;
                }
            }
            """);
        SymbolIndexTool.invalidate(javaFile);

        Map<String, Object> newMethodResp = findSymbol.runAsync(Map.of("query", "refundOrder"), null).blockingGet();
        List<Map<String, Object>> newMethods = (List<Map<String, Object>>) newMethodResp.get("symbols");
        assertEquals(1, newMethods.size());
        assertEquals("refundOrder", newMethods.get(0).get("name"));
        assertEquals("METHOD", newMethods.get(0).get("kind"));
    }

    @Test
    void jobScheduleManagerLifecycleAndTriggers() {
        JobScheduleManager manager = JobScheduleManager.getInstance();
        assertTrue(manager.listSchedules().isEmpty());

        // 1. Create interval schedule and triggerOnEdit schedule
        ScheduledJob intervalJob = manager.addSchedule(
            "Periodic Audit",
            "Run security check on dependencies",
            "SecurityAuditor",
            300,
            false
        );
        ScheduledJob editJob = manager.addSchedule(
            "On-Edit Linter",
            "Verify style and formatting of modified files",
            "Coder",
            0,
            true
        );

        assertNotNull(intervalJob.id);
        assertNotNull(editJob.id);
        assertTrue(intervalJob.enabled);
        assertEquals(0, intervalJob.runCount);
        assertEquals(300, intervalJob.intervalSeconds);
        assertFalse(intervalJob.triggerOnEdit);

        assertTrue(editJob.enabled);
        assertEquals(0, editJob.intervalSeconds);
        assertTrue(editJob.triggerOnEdit);

        // 2. List schedules
        List<ScheduledJob> list = manager.listSchedules();
        assertEquals(2, list.size());

        // 3. Toggle enable/disable
        assertTrue(manager.toggleSchedule(intervalJob.id, false));
        assertFalse(manager.getSchedule(intervalJob.id).enabled);

        assertTrue(manager.toggleSchedule(intervalJob.id, true));
        assertTrue(manager.getSchedule(intervalJob.id).enabled);

        // 4. triggerNow(id) increments runCount and updates lastRunAt
        assertTrue(manager.triggerNow(intervalJob.id));
        assertEquals(1, manager.getSchedule(intervalJob.id).runCount);
        assertTrue(manager.getSchedule(intervalJob.id).lastRunAt > 0);

        assertTrue(manager.triggerNow(intervalJob.id));
        assertEquals(2, manager.getSchedule(intervalJob.id).runCount);

        // 5. notifyFileEdited triggers enabled triggerOnEdit schedule and respects 15s debounce
        int triggeredFirst = manager.notifyFileEdited("src/Main.java");
        assertEquals(1, triggeredFirst);
        assertEquals(1, manager.getSchedule(editJob.id).runCount);

        int triggeredDebounce = manager.notifyFileEdited("src/Main.java");
        assertEquals(0, triggeredDebounce);
        assertEquals(1, manager.getSchedule(editJob.id).runCount);

        // 6. Delete schedules
        assertTrue(manager.removeSchedule(intervalJob.id));
        assertEquals(1, manager.listSchedules().size());
        assertNull(manager.getSchedule(intervalJob.id));

        assertTrue(manager.removeSchedule(editJob.id));
        assertTrue(manager.listSchedules().isEmpty());
    }
}
