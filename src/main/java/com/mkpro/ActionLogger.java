package com.mkpro;

import java.util.ArrayList;
import java.util.List;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

public class ActionLogger {
    private DB db;
    private List<String> logs;

    public ActionLogger(String dbPath) {
        db = DBMaker.fileDB(dbPath).make();
        logs = db.indexTreeList("logs", Serializer.STRING).createOrOpen();
    }

    public void log(String role, String content) {
        String entry = String.format("[%s] %s: %s", java.time.LocalDateTime.now(), role, content);
        logs.add(entry);
        db.commit();
    }

    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }
    
    public List<String> getRecentLogs(int limit) {
        int size = logs.size();
        int start = Math.max(0, size - limit);
        List<String> recent = new ArrayList<>();
        for (int i = start; i < size; i++) {
            recent.add(logs.get(i));
        }
        return recent;
    }

    // New Import Method
    public void importLog(String role, String content, String timestamp) {
        String entry = String.format("[%s] %s: %s", timestamp, role, content);
        logs.add(entry);
        db.commit();
    }

    public void close() {
        db.close();
    }
}
