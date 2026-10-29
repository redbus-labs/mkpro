package com.mkpro.infra.mq;

import com.mkpro.CentralMemory;
import java.io.Serializable;

public class MqConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final String STORE_KEY = "mq_config";

    private String mode = "AUTO";
    private int internalHttpPort = 8765;
    private String host = "localhost";
    private int port = 5672;
    private String username = "guest";
    private String password = "guest";
    private String virtualHost = "/";
    private String taskQueue = "mkpro.tasks";
    private String resultQueue = "mkpro.results";
    private String dlqQueue = "mkpro.tasks.dlq";
    private int prefetchCount = 1;
    private long taskTimeoutSeconds = 300;
    private boolean autoStart = false;

    public MqConfig() {}

    public MqConfig(String host, int port, String username, String password, String taskQueue, String resultQueue) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.taskQueue = taskQueue;
        this.resultQueue = resultQueue;
    }

    public static MqConfig fromEnvAndMemory(CentralMemory centralMemory) {
        MqConfig cfg = null;
        if (centralMemory != null) {
            try { cfg = centralMemory.get(STORE_KEY, MqConfig.class); } catch (Exception ignored) {}
        }
        if (cfg == null) cfg = new MqConfig();
        String m = propOrEnv("mq.mode", "RABBITMQ_MODE");
        if (m == null) m = propOrEnv("mkpro.mq.mode", "RABBITMQ_MODE");
        if (m != null) cfg.setMode(m);
        String ip = propOrEnv("mq.internal.port", "RABBITMQ_INTERNAL_PORT");
        if (ip == null) ip = propOrEnv("mkpro.mq.internal.port", "RABBITMQ_INTERNAL_PORT");
        if (ip != null) { try { cfg.setInternalHttpPort(Integer.parseInt(ip)); } catch (NumberFormatException ignored) {} }
        String h = propOrEnv("mkpro.mq.host", "RABBITMQ_HOST");
        if (h != null) cfg.setHost(h);
        String p = propOrEnv("mkpro.mq.port", "RABBITMQ_PORT");
        if (p != null) { try { cfg.setPort(Integer.parseInt(p)); } catch (NumberFormatException ignored) {} }
        String u = propOrEnv("mkpro.mq.username", "RABBITMQ_USER");
        if (u != null) cfg.setUsername(u);
        String pw = propOrEnv("mkpro.mq.password", "RABBITMQ_PASSWORD");
        if (pw != null) cfg.setPassword(pw);
        String vh = propOrEnv("mkpro.mq.vhost", "RABBITMQ_VHOST");
        if (vh != null) cfg.setVirtualHost(vh);
        String tq = propOrEnv("mkpro.mq.taskQueue", "RABBITMQ_TASK_QUEUE");
        if (tq != null) cfg.setTaskQueue(tq);
        String rq = propOrEnv("mkpro.mq.resultQueue", "RABBITMQ_RESULT_QUEUE");
        if (rq != null) cfg.setResultQueue(rq);
        String dq = propOrEnv("mkpro.mq.dlqQueue", "RABBITMQ_DLQ_QUEUE");
        if (dq != null) cfg.setDlqQueue(dq);
        return cfg;
    }

    public void saveToMemory(CentralMemory cm) {
        if (cm != null) cm.put(STORE_KEY, this);
    }

    private static String propOrEnv(String prop, String env) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return (v != null && !v.isBlank()) ? v.trim() : null;
    }

    public String getMode() { return mode != null ? mode : "AUTO"; }
    public void setMode(String mode) { if (mode == null || mode.isBlank()) { this.mode = "AUTO"; return; } String n = mode.trim().toUpperCase(); this.mode = ("INTERNAL".equals(n) || "RABBITMQ".equals(n) || "AUTO".equals(n)) ? n : "AUTO"; }
    public int getInternalHttpPort() { return internalHttpPort > 0 ? internalHttpPort : 8765; }
    public void setInternalHttpPort(int internalHttpPort) { this.internalHttpPort = internalHttpPort > 0 ? internalHttpPort : 8765; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getVirtualHost() { return virtualHost; }
    public void setVirtualHost(String virtualHost) { this.virtualHost = virtualHost; }
    public String getTaskQueue() { return taskQueue; }
    public void setTaskQueue(String taskQueue) { this.taskQueue = taskQueue; }
    public String getResultQueue() { return resultQueue; }
    public void setResultQueue(String resultQueue) { this.resultQueue = resultQueue; }
    public String getDlqQueue() { return dlqQueue; }
    public void setDlqQueue(String dlqQueue) { this.dlqQueue = dlqQueue; }
    public int getPrefetchCount() { return prefetchCount; }
    public void setPrefetchCount(int prefetchCount) { this.prefetchCount = Math.max(1, prefetchCount); }
    public long getTaskTimeoutSeconds() { return taskTimeoutSeconds; }
    public void setTaskTimeoutSeconds(long taskTimeoutSeconds) { this.taskTimeoutSeconds = Math.max(1, taskTimeoutSeconds); }
    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }
}
