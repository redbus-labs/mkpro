package com.mkpro;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
public class ActionLoggerTest {
 @TempDir Path tempDir;
 private ActionLogger logger;
 private String dbPath;
 private static final String ACTION_LOG_FILE = "action_log.txt";
 @BeforeEach void setUp() {
 dbPath = tempDir.resolve("test_logs.db").toString();
 logger = new ActionLogger(dbPath);
 File logFile = new File(ACTION_LOG_FILE);
 if (logFile.exists()) logFile.delete();
 }
 @AfterEach void tearDown() {
 if (logger != null) logger.close();
 File logFile = new File(ACTION_LOG_FILE);
 if (logFile.exists()) logFile.delete();
 }
 @Test @DisplayName("Test Case 1: File Logging") void testFileLogging() throws IOException {
 String action = "Test Action";
 logger.logAction(action);
 File logFile = new File(ACTION_LOG_FILE);
 assertTrue(logFile.exists());
 List<String> lines = Files.readAllLines(logFile.toPath());
 assertFalse(lines.isEmpty());
 boolean found = false;
 for (String line : lines) { if (line.contains("ACTION: " + action)) { found = true; break; } }
 assertTrue(found);
 }
 @Test @DisplayName("Test Case 2: In-Memory Buffer and Size Limit") void testInMemoryBufferLimit() {
 for (int i = 0; i < 600; i++) logger.logAction("Action " + i);
 List<String> buffer = logger.getMemoryBuffer();
 assertEquals(500, buffer.size());
 assertTrue(buffer.get(0).contains("Action 100"));
 assertTrue(buffer.get(499).contains("Action 599"));
 }
 @Test @DisplayName("Test Case 3: WebSocket Broadcast") void testWebSocketBroadcast() {
 class MockWS extends SimpleWebSocketServer {
 String last; int count = 0;
 MockWS() { super(0); }
 @Override public void broadcast(String msg) { this.last = msg; this.count++; }
 @Override public void start() {}
 }
 MockWS mockWs = new MockWS();
 logger.setWebSocketServer(mockWs);
 logger.logAction("Broadcast Test");
 assertEquals(1, mockWs.count);
 assertTrue(mockWs.last.contains("Broadcast Test"));
 }
 @Test @DisplayName("Test Case 4: Thread Safety") void testThreadSafety() throws InterruptedException {
 int tc = 10; int lpt = 100; Thread[] ts = new Thread[tc];
 for (int i = 0; i < tc; i++) {
 ts[i] = new Thread(() -> { for (int j = 0; j < lpt; j++) logger.logAction("Thread Action"); });
 ts[i].start();
 }
 for (Thread t : ts) t.join();
 assertEquals(500, logger.getMemoryBuffer().size());
 }
 @Test @DisplayName("Test Case 5: Clear Logs") void testClearMemoryLogs() {
 logger.logAction("Action 1");
 assertFalse(logger.getMemoryBuffer().isEmpty());
 logger.clearMemoryLogs();
 assertTrue(logger.getMemoryBuffer().isEmpty());
 }
}
