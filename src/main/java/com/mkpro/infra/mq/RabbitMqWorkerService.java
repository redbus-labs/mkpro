package com.mkpro.infra.mq;

import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.mkpro.core.MkProContext;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class RabbitMqWorkerService {

    @FunctionalInterface
    public interface TaskExecutor {
        String executeTask(MqTaskMessage task) throws Exception;
    }

    private final MqConfig config;
    private final ConnectionFactory connectionFactory;
    private final TaskExecutor taskExecutor;
    private final String workerInstanceId;

    private volatile Connection connection;
    private volatile Channel channel;
    private volatile String consumerTag;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tasksProcessed = new AtomicLong(0);
    private final AtomicLong tasksSucceeded = new AtomicLong(0);
    private final AtomicLong tasksFailed = new AtomicLong(0);
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private volatile String lastTaskId = null;
    private volatile long lastTaskAt = 0L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BlockingQueue<MqTaskMessage> internalTaskQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<MqResultMessage> internalResultQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, MqResultMessage> resultsById = new ConcurrentHashMap<>();
    private final Deque<MqTaskMessage> internalDlq = new ConcurrentLinkedDeque<>();
    private volatile Thread internalWorkerThread;
    private volatile HttpServer internalHttpServer;
    private volatile int boundInternalHttpPort = -1;
    private volatile String activeMode = "NONE";

    public RabbitMqWorkerService(MqConfig config, MkProContext context) {
        this(config, createConnectionFactory(config), createRunnerTaskExecutor(context, config), resolveInstanceId(context));
    }

    public RabbitMqWorkerService(MqConfig config, TaskExecutor taskExecutor, String workerInstanceId) {
        this(config, createConnectionFactory(config), taskExecutor, workerInstanceId);
    }

    public RabbitMqWorkerService(MqConfig config, ConnectionFactory connectionFactory, TaskExecutor taskExecutor, String workerInstanceId) {
        this.config = config != null ? config : new MqConfig();
        this.connectionFactory = connectionFactory != null ? connectionFactory : createConnectionFactory(this.config);
        this.taskExecutor = taskExecutor;
        this.workerInstanceId = (workerInstanceId != null && !workerInstanceId.isBlank())
                ? workerInstanceId
                : "worker-" + UUID.randomUUID().toString().substring(0, 6);
    }

    public static ConnectionFactory createConnectionFactory(MqConfig cfg) {
        MqConfig c = cfg != null ? cfg : new MqConfig();
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(c.getHost());
        factory.setPort(c.getPort());
        factory.setUsername(c.getUsername());
        factory.setPassword(c.getPassword());
        factory.setVirtualHost(c.getVirtualHost());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);
        return factory;
    }

    private static String resolveInstanceId(MkProContext context) {
        if (context != null && context.getInstanceName() != null && !context.getInstanceName().isBlank()) {
            return context.getInstanceName();
        }
        return "worker-" + UUID.randomUUID().toString().substring(0, 6);
    }

    public synchronized void start() throws Exception {
        if (running.get()) {
            return;
        }
        String desired = config.getMode() != null ? config.getMode().trim().toUpperCase() : "AUTO";
        if ("INTERNAL".equals(desired)) {
            startInternalMode();
        } else if ("RABBITMQ".equals(desired)) {
            startRabbitMqMode();
        } else {
            try {
                startRabbitMqMode();
            } catch (Exception e) {
                System.out.println("[MQ] RabbitMQ unreachable — started Built-In Internal Queue Worker");
                startInternalMode();
            }
        }
    }

    private void startRabbitMqMode() throws Exception {
        this.connection = connectionFactory.newConnection("mkpro-worker-" + workerInstanceId);
        this.channel = connection.createChannel();
        declareTopology(this.channel);
        DeliverCallback deliverCallback = this::handleDelivery;
        CancelCallback cancelCallback = tag -> running.set(false);
        this.consumerTag = this.channel.basicConsume(config.getTaskQueue(), false, deliverCallback, cancelCallback);
        this.activeMode = "RABBITMQ";
        this.running.set(true);
    }

    private void startInternalMode() throws Exception {
        this.activeMode = "INTERNAL";
        this.running.set(true);
        startInternalHttpServer();
        this.internalWorkerThread = new Thread(this::runInternalWorkerLoop, "mkpro-internal-mq-worker");
        this.internalWorkerThread.setDaemon(true);
        this.internalWorkerThread.start();
    }

    public void declareTopology(Channel ch) throws IOException {
        if (config.getDlqQueue() != null && !config.getDlqQueue().isBlank()) {
            ch.queueDeclare(config.getDlqQueue(), true, false, false, null);
        }
        Map<String, Object> args = new HashMap<>();
        if (config.getDlqQueue() != null && !config.getDlqQueue().isBlank()) {
            args.put("x-dead-letter-exchange", "");
            args.put("x-dead-letter-routing-key", config.getDlqQueue());
        }
        ch.queueDeclare(config.getTaskQueue(), true, false, false, args.isEmpty() ? null : args);
        ch.queueDeclare(config.getResultQueue(), true, false, false, null);
        ch.basicQos(config.getPrefetchCount());
    }

    public void handleDelivery(String tag, Delivery delivery) throws IOException {
        long deliveryTag = delivery.getEnvelope() != null ? delivery.getEnvelope().getDeliveryTag() : 0L;
        String bodyStr = new String(delivery.getBody(), StandardCharsets.UTF_8);
        MqTaskMessage task;
        try {
            task = MqTaskMessage.fromJson(bodyStr);
            if (task.getPrompt() == null || task.getPrompt().isBlank()) {
                throw new IllegalArgumentException("Task prompt is empty");
            }
        } catch (Exception e) {
            tasksProcessed.incrementAndGet();
            tasksFailed.incrementAndGet();
            if (channel != null) {
                channel.basicNack(deliveryTag, false, false);
            }
            return;
        }

        AMQP.BasicProperties props = delivery.getProperties();
        if (props != null) {
            if ((task.getCorrelationId() == null || task.getCorrelationId().isBlank()) && props.getCorrelationId() != null) {
                task.setCorrelationId(props.getCorrelationId());
            }
            if ((task.getReplyTo() == null || task.getReplyTo().isBlank()) && props.getReplyTo() != null) {
                task.setReplyTo(props.getReplyTo());
            }
        }

        activeTasks.incrementAndGet();
        long startMs = System.currentTimeMillis();
        lastTaskId = task.getTaskId();
        lastTaskAt = startMs;

        MqResultMessage resultMsg;
        try {
            String output = taskExecutor != null ? taskExecutor.executeTask(task) : "";
            long duration = System.currentTimeMillis() - startMs;
            resultMsg = MqResultMessage.success(
                    task.getTaskId(),
                    task.getCorrelationId(),
                    output,
                    workerInstanceId,
                    task.getTargetAgent(),
                    duration
            );
            tasksSucceeded.incrementAndGet();
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startMs;
            resultMsg = MqResultMessage.error(
                    task.getTaskId(),
                    task.getCorrelationId(),
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(),
                    workerInstanceId,
                    task.getTargetAgent(),
                    duration
            );
            tasksFailed.incrementAndGet();
        } finally {
            tasksProcessed.incrementAndGet();
            activeTasks.decrementAndGet();
        }

        try {
            String replyQueue = (task.getReplyTo() != null && !task.getReplyTo().isBlank())
                    ? task.getReplyTo()
                    : config.getResultQueue();
            byte[] resultBytes = resultMsg.toJson().getBytes(StandardCharsets.UTF_8);
            AMQP.BasicProperties replyProps = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .correlationId(resultMsg.getCorrelationId())
                    .build();
            if (channel != null) {
                channel.basicPublish("", replyQueue, replyProps, resultBytes);
                channel.basicAck(deliveryTag, false);
            }
        } catch (Exception pubEx) {
            if (channel != null) {
                channel.basicNack(deliveryTag, false, false);
            }
        }
    }

    public String publishTask(String prompt, String targetAgent, String replyTo) throws Exception {
        MqTaskMessage task = new MqTaskMessage(UUID.randomUUID().toString(), prompt, targetAgent);
        task.setReplyTo(replyTo != null && !replyTo.isBlank() ? replyTo : config.getResultQueue());
        task.setCorrelationId(task.getTaskId());
        byte[] payload = task.toJson().getBytes(StandardCharsets.UTF_8);
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2)
                .correlationId(task.getCorrelationId())
                .replyTo(task.getReplyTo())
                .build();

        if ("INTERNAL".equalsIgnoreCase(activeMode) || "INTERNAL".equalsIgnoreCase(config.getMode())) {
            enqueueInternalTask(task);
            return task.getTaskId();
        }
        if (running.get() && channel != null && channel.isOpen()) {
            channel.basicPublish("", config.getTaskQueue(), props, payload);
        } else {
            try (Connection tempConn = connectionFactory.newConnection("mkpro-sender");
                 Channel tempCh = tempConn.createChannel()) {
                declareTopology(tempCh);
                tempCh.basicPublish("", config.getTaskQueue(), props, payload);
            } catch (Exception ex) {
                if ("AUTO".equalsIgnoreCase(config.getMode())) {
                    if (!running.get()) startInternalMode();
                    enqueueInternalTask(task);
                    return task.getTaskId();
                }
                throw ex;
            }
        }
        return task.getTaskId();
    }

    public synchronized void stop() {
        running.set(false);
        try {
            if (channel != null && consumerTag != null && channel.isOpen()) {
                channel.basicCancel(consumerTag);
            }
        } catch (Exception ignored) {}
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception ignored) {}
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception ignored) {}
        if (internalWorkerThread != null) {
            try { internalWorkerThread.interrupt(); } catch (Exception ignored) {}
            internalWorkerThread = null;
        }
        if (internalHttpServer != null) {
            try { internalHttpServer.stop(0); } catch (Exception ignored) {}
            internalHttpServer = null;
        }
        boundInternalHttpPort = -1;
        activeMode = "NONE";
        consumerTag = null;
        channel = null;
        connection = null;
    }

    public static TaskExecutor createRunnerTaskExecutor(MkProContext context, MqConfig config) {
        return task -> {
            if (context == null || context.getRunner() == null || context.getSessionService() == null) {
                throw new IllegalStateException("MkPro Runner or SessionService is not initialized");
            }
            String sessionId = (task.getSessionId() != null && !task.getSessionId().isBlank())
                    ? task.getSessionId()
                    : "mq-task-" + task.getTaskId();
            String agentName = (task.getTargetAgent() != null && !task.getTargetAgent().isBlank())
                    ? task.getTargetAgent()
                    : "Coordinator";
            SessionKey sessionKey = new SessionKey("mkpro", agentName, sessionId);
            Session session = null;
            try {
                session = context.getSessionService().getSession(sessionKey, GetSessionConfig.builder().build()).blockingGet();
            } catch (Exception ignored) {}
            if (session == null) {
                session = context.getSessionService().createSession(sessionKey, new HashMap<>()).blockingGet();
            }
            Content message = Content.fromParts(new Part[]{Part.fromText(task.getPrompt())});
            StringBuilder responseText = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            context.getRunner().runAsync(session.sessionKey(), message)
                    .blockingSubscribe(
                            event -> event.content().ifPresent(c -> c.parts().ifPresent(parts -> {
                                for (Part part : parts) {
                                    part.text().ifPresent(responseText::append);
                                }
                            })),
                            err -> { errorRef.set(err); latch.countDown(); },
                            latch::countDown
                    );
            long timeoutSec = config != null ? config.getTaskTimeoutSeconds() : 300L;
            boolean completed = latch.await(timeoutSec, TimeUnit.SECONDS);
            if (!completed) {
                throw new RuntimeException("Task timed out after " + timeoutSec + "s");
            }
            if (errorRef.get() != null) {
                throw new RuntimeException(errorRef.get().getMessage(), errorRef.get());
            }
            return responseText.toString().trim();
        };
    }

    public void enqueueInternalTask(MqTaskMessage task) {
        if (task == null) return;
        if (task.getCorrelationId() == null || task.getCorrelationId().isBlank()) {
            task.setCorrelationId(task.getTaskId());
        }
        internalTaskQueue.offer(task);
    }

    private void runInternalWorkerLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                MqTaskMessage task = internalTaskQueue.poll(50, TimeUnit.MILLISECONDS);
                if (task != null) {
                    processInternalTask(task);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {}
        }
    }

    public void processInternalTask(MqTaskMessage task) {
        if (task == null || task.getPrompt() == null || task.getPrompt().isBlank()) {
            if (task != null) internalDlq.addLast(task);
            tasksProcessed.incrementAndGet();
            tasksFailed.incrementAndGet();
            return;
        }
        if (task.getCorrelationId() == null || task.getCorrelationId().isBlank()) {
            task.setCorrelationId(task.getTaskId());
        }
        activeTasks.incrementAndGet();
        long startMs = System.currentTimeMillis();
        lastTaskId = task.getTaskId();
        lastTaskAt = startMs;
        MqResultMessage resultMsg;
        try {
            String output = taskExecutor != null ? taskExecutor.executeTask(task) : "";
            long duration = System.currentTimeMillis() - startMs;
            resultMsg = MqResultMessage.success(task.getTaskId(), task.getCorrelationId(), output, workerInstanceId, task.getTargetAgent(), duration);
            tasksSucceeded.incrementAndGet();
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startMs;
            resultMsg = MqResultMessage.error(task.getTaskId(), task.getCorrelationId(), ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(), workerInstanceId, task.getTargetAgent(), duration);
            tasksFailed.incrementAndGet();
        } finally {
            tasksProcessed.incrementAndGet();
            activeTasks.decrementAndGet();
        }
        resultsById.put(task.getTaskId(), resultMsg);
        internalResultQueue.offer(resultMsg);
    }

    private void startInternalHttpServer() {
        if (internalHttpServer != null) return;
        int desiredPort = config.getInternalHttpPort();
        HttpServer srv = null;
        try {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", desiredPort), 0);
        } catch (IOException e) {
            try {
                srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException ignored) {}
        }
        if (srv == null) return;
        this.internalHttpServer = srv;
        this.boundInternalHttpPort = srv.getAddress().getPort();
        srv.createContext("/mq/tasks", this::handleHttpTasks);
        srv.createContext("/mq/results", this::handleHttpResults);
        srv.createContext("/mq/status", this::handleHttpStatus);
        srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mkpro-internal-mq-http");
            t.setDaemon(true);
            return t;
        }));
        srv.start();
    }

    private void handleHttpTasks(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            MqTaskMessage task = MqTaskMessage.fromJson(body);
            if (task.getPrompt() == null || task.getPrompt().isBlank()) {
                internalDlq.addLast(task);
                tasksProcessed.incrementAndGet();
                tasksFailed.incrementAndGet();
                sendJson(ex, 400, "{\"error\":\"Empty prompt\"}");
                return;
            }
            enqueueInternalTask(task);
            Map<String, Object> resp = new HashMap<>();
            resp.put("taskId", task.getTaskId());
            resp.put("correlationId", task.getCorrelationId());
            resp.put("status", "ENQUEUED");
            sendJson(ex, 200, MAPPER.writeValueAsString(resp));
        } catch (Exception e) {
            MqTaskMessage poison = new MqTaskMessage();
            poison.setPrompt(body);
            internalDlq.addLast(poison);
            tasksProcessed.incrementAndGet();
            tasksFailed.incrementAndGet();
            sendJson(ex, 400, "{\"error\":\"Invalid task payload\"}");
        }
    }

    private void handleHttpResults(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI());
        String taskId = q.get("taskId");
        long timeoutSec = 30L;
        if (q.get("timeout") != null) {
            try { timeoutSec = Long.parseLong(q.get("timeout")); } catch (NumberFormatException ignored) {}
        }
        try {
            if (taskId != null && !taskId.isBlank()) {
                MqResultMessage res = pollResult(taskId.trim(), Math.max(0L, timeoutSec * 1000L));
                if (res != null) {
                    sendJson(ex, 200, res.toJson());
                } else {
                    sendJson(ex, 404, "{\"status\":\"PENDING\",\"taskId\":\"" + taskId + "\"}");
                }
                return;
            }
            if ("true".equalsIgnoreCase(q.get("poll"))) {
                MqResultMessage res = internalResultQueue.poll(Math.max(1L, timeoutSec), TimeUnit.SECONDS);
                if (res != null) {
                    sendJson(ex, 200, res.toJson());
                } else {
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                }
                return;
            }
            int limit = 10;
            if (q.get("limit") != null) {
                try { limit = Integer.parseInt(q.get("limit")); } catch (NumberFormatException ignored) {}
            }
            sendJson(ex, 200, MAPPER.writeValueAsString(getRecentResults(limit)));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + (e.getMessage() != null ? e.getMessage() : "Error") + "\"}");
        }
    }

    private void handleHttpStatus(HttpExchange ex) throws IOException {
        Map<String, Object> st = new HashMap<>();
        st.put("mode", getActiveMode());
        st.put("running", isRunning());
        st.put("workerInstanceId", workerInstanceId);
        st.put("internalHttpPort", getBoundInternalHttpPort());
        st.put("pendingCount", getInternalPendingCount());
        st.put("resultCount", getInternalResultCount());
        st.put("dlqCount", getInternalDlqCount());
        st.put("tasksProcessed", getTasksProcessed());
        st.put("tasksSucceeded", getTasksSucceeded());
        st.put("tasksFailed", getTasksFailed());
        st.put("activeTasks", getActiveTasks());
        sendJson(ex, 200, MAPPER.writeValueAsString(st));
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        if (uri == null || uri.getRawQuery() == null) return map;
        for (String pair : uri.getRawQuery().split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public int getInternalPendingCount() { return internalTaskQueue.size(); }
    public int getInternalResultCount() { return internalResultQueue.size(); }
    public int getInternalDlqCount() { return internalDlq.size(); }

    public List<MqResultMessage> getRecentResults(int limit) {
        int max = Math.max(1, limit);
        List<MqResultMessage> all = new ArrayList<>(internalResultQueue);
        Collections.reverse(all);
        return all.size() <= max ? all : new ArrayList<>(all.subList(0, max));
    }

    public MqResultMessage pollResult(String taskId, long timeoutMs) throws InterruptedException {
        if (taskId == null || taskId.isBlank()) return null;
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            MqResultMessage res = resultsById.get(taskId);
            if (res != null) return res;
            if (System.currentTimeMillis() >= deadline) return null;
            Thread.sleep(20L);
        }
    }

    public String getActiveMode() { return activeMode; }
    public int getBoundInternalHttpPort() { return boundInternalHttpPort > 0 ? boundInternalHttpPort : config.getInternalHttpPort(); }
    public BlockingQueue<MqTaskMessage> getInternalTaskQueue() { return internalTaskQueue; }
    public BlockingQueue<MqResultMessage> getInternalResultQueue() { return internalResultQueue; }
    public ConcurrentHashMap<String, MqResultMessage> getResultsById() { return resultsById; }
    public Deque<MqTaskMessage> getInternalDlq() { return internalDlq; }

    public boolean isRunning() { return running.get(); }
    public MqConfig getConfig() { return config; }
    public long getTasksProcessed() { return tasksProcessed.get(); }
    public long getTasksSucceeded() { return tasksSucceeded.get(); }
    public long getTasksFailed() { return tasksFailed.get(); }
    public int getActiveTasks() { return activeTasks.get(); }
    public String getWorkerInstanceId() { return workerInstanceId; }
    public String getConsumerTag() { return consumerTag; }
    public String getLastTaskId() { return lastTaskId; }
    public long getLastTaskAt() { return lastTaskAt; }
}
