package com.mkpro.knowledge;

import com.mkpro.CentralMemory;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KnowledgeStore — direct persistence via CentralMemory.
 */
public class KnowledgeStoreTest {

    private Path tempDir;
    private CentralMemory centralMemory;
    private KnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ks-test-");
        Path sharedDb = tempDir.resolve("shared").resolve("central_memory.db");
        Path localDb = tempDir.resolve("local").resolve("stats.db");
        centralMemory = new CentralMemory(sharedDb, localDb);
        store = new KnowledgeStore(centralMemory);
    }

    @AfterEach
    void tearDown() {
        if (centralMemory != null) {
            centralMemory.close();
            centralMemory = null;
        }
        // Don't force-delete tempDir on Windows — MapDB WAL files may still be locked
    }

    @Test
    void saveAndGetReport() {
        TopicReport report = new TopicReport();
        report.setName("k8s-hpa");
        report.setSummary("Kubernetes HPA autoscaling documentation summary");

        store.saveReport(report);

        TopicReport loaded = store.getReport("k8s-hpa");
        assertNotNull(loaded);
        assertEquals("k8s-hpa", loaded.getName());
        assertEquals("Kubernetes HPA autoscaling documentation summary", loaded.getSummary());
    }

    @Test
    void getNonExistentReturnsNull() {
        assertNull(store.getReport("nonexistent"));
    }

    @Test
    void getNullNameReturnsNull() {
        assertNull(store.getReport(null));
        assertNull(store.getReport(""));
        assertNull(store.getReport("   "));
    }

    @Test
    void saveNullReportIgnored() {
        // Should not throw
        assertDoesNotThrow(() -> store.saveReport(null));
    }

    @Test
    void saveReportWithNullNameIgnored() {
        TopicReport report = new TopicReport();
        report.setName(null);
        assertDoesNotThrow(() -> store.saveReport(report));
    }

    @Test
    void deleteReport() {
        TopicReport report = new TopicReport();
        report.setName("to-delete");
        report.setSummary("Will be deleted");

        store.saveReport(report);
        assertNotNull(store.getReport("to-delete"));

        store.deleteReport("to-delete");
        assertNull(store.getReport("to-delete"));
    }

    @Test
    void deleteNullIgnored() {
        assertDoesNotThrow(() -> store.deleteReport(null));
        assertDoesNotThrow(() -> store.deleteReport(""));
    }

    @Test
    void getAllReportsEmpty() {
        List<TopicReport> reports = store.getAllReports();
        assertNotNull(reports);
        assertTrue(reports.isEmpty());
    }

    @Test
    void getAllReportsMultiple() {
        TopicReport r1 = new TopicReport();
        r1.setName("topic-a");
        r1.setSummary("Summary A");
        store.saveReport(r1);

        TopicReport r2 = new TopicReport();
        r2.setName("topic-b");
        r2.setSummary("Summary B");
        store.saveReport(r2);

        List<TopicReport> all = store.getAllReports();
        assertEquals(2, all.size());
    }

    @Test
    void getAllReportsExcludesDeleted() {
        TopicReport r1 = new TopicReport();
        r1.setName("keep");
        r1.setSummary("Keeping this");
        store.saveReport(r1);

        TopicReport r2 = new TopicReport();
        r2.setName("delete-me");
        r2.setSummary("Will be deleted");
        store.saveReport(r2);

        store.deleteReport("delete-me");

        List<TopicReport> all = store.getAllReports();
        assertEquals(1, all.size());
        assertEquals("keep", all.get(0).getName());
    }

    @Test
    void overwriteExistingReport() {
        TopicReport r1 = new TopicReport();
        r1.setName("evolving");
        r1.setSummary("Version 1");
        store.saveReport(r1);

        TopicReport r2 = new TopicReport();
        r2.setName("evolving");
        r2.setSummary("Version 2 — updated with new data");
        store.saveReport(r2);

        TopicReport loaded = store.getReport("evolving");
        assertEquals("Version 2 — updated with new data", loaded.getSummary());
    }
}
