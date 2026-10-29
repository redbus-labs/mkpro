package com.mkpro.infra.mq;

import com.mkpro.CentralMemory;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MqTestClient {
    private static final String ANSI_RESET = "\u001b[0m";
    private static final String ANSI_CYAN = "\u001b[36m";
    private static final String ANSI_GREEN = "\u001b[32m";
    private static final String ANSI_YELLOW = "\u001b[33m";
    private static final String ANSI_RED = "\u001b[31m";
    private static final String ANSI_BOLD = "\u001b[1m";
    private static final String ANSI_DIM = "\u001b[2m";

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            printHelp();
            return;
        }
        boolean sendOnly = false;
        boolean listenMode = false;
        boolean internalFlag = false;
        Integer internalPortOverride = null;
        String hostOverride = null;
        Integer portOverride = null;
        String agent = "Coordinator";
        String sessionId = null;
        long timeoutSeconds = 120L;
        StringBuilder promptBuilder = new StringBuilder();
        for (int i = 0; i + 1 != args.length + 1; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    printHelp();
                    return;
                }
                case "--send-only" -> sendOnly = true;
                case "--listen" -> listenMode = true;
                case "--internal" -> internalFlag = true;
                case "--internal-port" -> {
                    if (i + 1 != args.length) {
                        try { internalPortOverride = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
                    }
                }
                case "--host" -> {
                    if (i + 1 != args.length) hostOverride = args[++i];
                }
                case "--port" -> {
                    if (i + 1 != args.length) {
                        try { portOverride = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
                    }
                }
                case "--agent" -> {
                    if (i + 1 != args.length) agent = args[++i];
                }
                case "--session" -> {
                    if (i + 1 != args.length) sessionId = args[++i];
                }
                case "--timeout" -> {
                    if (i + 1 != args.length) {
                        try { timeoutSeconds = Long.parseLong(args[++i]); } catch (NumberFormatException ignored) {}
                    }
                }
                default -> {
                    if (promptBuilder.length() != 0) promptBuilder.append(" ");
                    promptBuilder.append(arg);
                }
            }
        }
        CentralMemory cm = null;
        try { cm = CentralMemory.getInstance(); } catch (Exception ignored) {}
        MqConfig config = MqConfig.fromEnvAndMemory(cm);
        if (hostOverride != null && !hostOverride.isBlank()) config.setHost(hostOverride);
        if (portOverride != null && portOverride != 0) config.setPort(portOverride);
        if (internalPortOverride != null && internalPortOverride > 0) config.setInternalHttpPort(internalPortOverride);
        if (internalFlag) config.setMode("INTERNAL");
        ConnectionFactory factory = RabbitMqWorkerService.createConnectionFactory(config);
        if (listenMode) {
            if ("INTERNAL".equalsIgnoreCase(config.getMode())) {
                runInternalListenMode(config);
            } else {
                runListenMode(factory, config);
            }
            return;
        }
        String prompt = promptBuilder.toString().trim();
        if (prompt.isEmpty()) {
            System.err.println(ANSI_RED + "Error: No prompt specified." + ANSI_RESET);
            printHelp();
            System.exit(1);
            return;
        }
        if ("INTERNAL".equalsIgnoreCase(config.getMode())) {
            if (sendOnly) runInternalSendOnly(config, prompt, agent, sessionId);
            else runInternalSendAndWait(config, prompt, agent, sessionId, timeoutSeconds);
            return;
        }
        if (sendOnly) {
            runSendOnly(factory, config, prompt, agent, sessionId);
        } else {
            runSendAndWait(factory, config, prompt, agent, sessionId, timeoutSeconds);
        }
    }

    private static void runSendOnly(ConnectionFactory factory, MqConfig config, String prompt, String agent, String sessionId) {
        String taskId = UUID.randomUUID().toString();
        MqTaskMessage task = new MqTaskMessage(taskId, prompt, agent);
        task.setSessionId(sessionId);
        task.setReplyTo(config.getResultQueue());
        task.setCorrelationId(taskId);
        try (Connection conn = factory.newConnection("mkpro-mq-test-sender");
             Channel ch = conn.createChannel()) {
            declareQueues(ch, config);
            byte[] payload = task.toJson().getBytes(StandardCharsets.UTF_8);
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .correlationId(task.getCorrelationId())
                    .replyTo(task.getReplyTo())
                    .build();
            ch.basicPublish("", config.getTaskQueue(), props, payload);
            System.out.println(ANSI_GREEN + "[MQ Test] Task published successfully (--send-only)" + ANSI_RESET);
            System.out.println("  Broker:       " + config.getHost() + ":" + config.getPort());
            System.out.println("  Task ID:      " + taskId);
            System.out.println("  Target Queue: " + config.getTaskQueue());
            System.out.println("  Target Agent: " + agent);
            if (sessionId != null) System.out.println("  Session ID:   " + sessionId);
            System.out.println("  Prompt:       " + prompt);
        } catch (Exception e) {
            if ("AUTO".equalsIgnoreCase(config.getMode())) {
                System.out.println(ANSI_YELLOW + "[MQ Test] RabbitMQ unreachable — falling back to Internal HTTP Bridge (http://127.0.0.1:" + config.getInternalHttpPort() + ")" + ANSI_RESET);
                runInternalSendOnly(config, prompt, agent, sessionId);
                return;
            }
            System.err.println(ANSI_RED + "[MQ Test] Failed to publish task: " + e.getMessage() + ANSI_RESET);
            System.exit(1);
        }
    }

    private static void runSendAndWait(ConnectionFactory factory, MqConfig config, String prompt, String agent, String sessionId, long timeoutSeconds) {
        String taskId = UUID.randomUUID().toString();
        MqTaskMessage task = new MqTaskMessage(taskId, prompt, agent);
        task.setSessionId(sessionId);
        task.setReplyTo(config.getResultQueue());
        task.setCorrelationId(taskId);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<MqResultMessage> resultRef = new AtomicReference<>();
        try (Connection conn = factory.newConnection("mkpro-mq-test-client");
             Channel ch = conn.createChannel()) {
            declareQueues(ch, config);
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                long dTag = delivery.getEnvelope().getDeliveryTag();
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                try {
                    MqResultMessage res = MqResultMessage.fromJson(body);
                    boolean match = taskId.equals(res.getTaskId()) || taskId.equals(res.getCorrelationId());
                    if (!match && delivery.getProperties() != null) {
                        match = taskId.equals(delivery.getProperties().getCorrelationId());
                    }
                    if (match) {
                        ch.basicAck(dTag, false);
                        resultRef.set(res);
                        latch.countDown();
                    } else {
                        ch.basicNack(dTag, false, true);
                    }
                } catch (Exception e) {
                    ch.basicNack(dTag, false, false);
                }
            };
            String cTag = ch.basicConsume(config.getResultQueue(), false, deliverCallback, tag -> {});
            byte[] payload = task.toJson().getBytes(StandardCharsets.UTF_8);
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .correlationId(task.getCorrelationId())
                    .replyTo(task.getReplyTo())
                    .build();
            ch.basicPublish("", config.getTaskQueue(), props, payload);
            System.out.println(ANSI_CYAN + "========================================" + ANSI_RESET);
            System.out.println(ANSI_BOLD + "  MKPRO RabbitMQ End-to-End Task Test" + ANSI_RESET);
            System.out.println(ANSI_CYAN + "========================================" + ANSI_RESET);
            System.out.println("  Broker:       " + config.getHost() + ":" + config.getPort());
            System.out.println("  Task ID:      " + ANSI_YELLOW + taskId + ANSI_RESET);
            System.out.println("  Target Queue: " + config.getTaskQueue());
            System.out.println("  Result Queue: " + config.getResultQueue());
            System.out.println("  Target Agent: " + agent);
            if (sessionId != null) System.out.println("  Session ID:   " + sessionId);
            System.out.println("  Prompt:       " + prompt);
            System.out.println(ANSI_DIM + "  Waiting up to " + timeoutSeconds + "s for worker response..." + ANSI_RESET);
            boolean received = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            try { ch.basicCancel(cTag); } catch (Exception ignored) {}
            if (!received) {
                System.err.println(ANSI_RED + "\n[MQ Test] Timed out after " + timeoutSeconds + "s waiting for response on '" + config.getResultQueue() + "'." + ANSI_RESET);
                System.exit(1);
                return;
            }
            MqResultMessage res = resultRef.get();
            printResult(res);
            if (!"SUCCESS".equalsIgnoreCase(res.getStatus())) {
                System.exit(1);
            }
        } catch (Exception e) {
            if ("AUTO".equalsIgnoreCase(config.getMode())) {
                System.out.println(ANSI_YELLOW + "[MQ Test] RabbitMQ unreachable — falling back to Internal HTTP Bridge (http://127.0.0.1:" + config.getInternalHttpPort() + ")" + ANSI_RESET);
                runInternalSendAndWait(config, prompt, agent, sessionId, timeoutSeconds);
                return;
            }
            System.err.println(ANSI_RED + "[MQ Test] Error: " + e.getMessage() + ANSI_RESET);
            System.exit(1);
        }
    }

    private static void runListenMode(ConnectionFactory factory, MqConfig config) {
        System.out.println(ANSI_CYAN + "[MQ Test] Listening continuously on result queue '" + config.getResultQueue() + "' (" + config.getHost() + ":" + config.getPort() + ")... Press Ctrl+C to stop." + ANSI_RESET);
        try (Connection conn = factory.newConnection("mkpro-mq-test-listener");
             Channel ch = conn.createChannel()) {
            declareQueues(ch, config);
            DeliverCallback callback = (consumerTag, delivery) -> {
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                try {
                    MqResultMessage res = MqResultMessage.fromJson(body);
                    printResult(res);
                } catch (Exception e) {
                    System.out.println(ANSI_YELLOW + "\n[Raw Message] " + body + ANSI_RESET);
                }
                ch.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            };
            ch.basicConsume(config.getResultQueue(), false, callback, tag -> {});
            new CountDownLatch(1).await();
        } catch (Exception e) {
            if ("AUTO".equalsIgnoreCase(config.getMode())) {
                System.out.println(ANSI_YELLOW + "[MQ Test] RabbitMQ unreachable — falling back to Internal HTTP Bridge (http://127.0.0.1:" + config.getInternalHttpPort() + ")" + ANSI_RESET);
                runInternalListenMode(config);
                return;
            }
            System.err.println(ANSI_RED + "[MQ Test] Listener error: " + e.getMessage() + ANSI_RESET);
            System.exit(1);
        }
    }

    public static void runInternalSendOnly(MqConfig config, String prompt, String agent, String sessionId) {
        String taskId = UUID.randomUUID().toString();
        MqTaskMessage task = new MqTaskMessage(taskId, prompt, agent);
        task.setSessionId(sessionId);
        task.setCorrelationId(taskId);
        String baseUrl = "http://127.0.0.1:" + config.getInternalHttpPort();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/mq/tasks")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(task.toJson(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
            System.out.println(ANSI_GREEN + "[MQ Test] Task published to Internal Queue (--send-only)" + ANSI_RESET);
            System.out.println("  Bridge:       " + baseUrl);
            System.out.println("  Task ID:      " + taskId);
            System.out.println("  Target Agent: " + agent);
            System.out.println("  Prompt:       " + prompt);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "[MQ Test] Failed to publish to Internal HTTP Bridge (" + baseUrl + "): " + e.getMessage() + ANSI_RESET);
            System.exit(1);
        }
    }

    public static void runInternalSendAndWait(MqConfig config, String prompt, String agent, String sessionId, long timeoutSeconds) {
        String taskId = UUID.randomUUID().toString();
        MqTaskMessage task = new MqTaskMessage(taskId, prompt, agent);
        task.setSessionId(sessionId);
        task.setCorrelationId(taskId);
        String baseUrl = "http://127.0.0.1:" + config.getInternalHttpPort();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest postReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/mq/tasks")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(task.toJson(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> postResp = client.send(postReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (postResp.statusCode() != 200) throw new RuntimeException("HTTP " + postResp.statusCode() + ": " + postResp.body());
            HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/mq/results?taskId=" + taskId + "&timeout=" + timeoutSeconds)).timeout(Duration.ofSeconds(timeoutSeconds + 5)).GET().build();
            HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (getResp.statusCode() != 200) {
                System.err.println(ANSI_RED + "\n[MQ Test] Timed out after " + timeoutSeconds + "s waiting for response." + ANSI_RESET);
                System.exit(1);
                return;
            }
            MqResultMessage res = MqResultMessage.fromJson(getResp.body());
            printResult(res);
            if (!"SUCCESS".equalsIgnoreCase(res.getStatus())) System.exit(1);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "[MQ Test] Internal Bridge Error (" + baseUrl + "): " + e.getMessage() + ANSI_RESET);
            System.exit(1);
        }
    }

    private static void runInternalListenMode(MqConfig config) {
        String baseUrl = "http://127.0.0.1:" + config.getInternalHttpPort();
        System.out.println(ANSI_CYAN + "[MQ Test] Listening continuously on Internal HTTP Bridge (" + baseUrl + ")... Press Ctrl+C to stop." + ANSI_RESET);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/mq/results?poll=true&timeout=5")).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    printResult(MqResultMessage.fromJson(resp.body()));
                }
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "[MQ Test] Internal Listener error: " + e.getMessage() + ANSI_RESET);
            System.exit(1);
        }
    }

    private static void declareQueues(Channel ch, MqConfig config) throws Exception {
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
    }

    private static void printResult(MqResultMessage res) {
        System.out.println();
        boolean ok = "SUCCESS".equalsIgnoreCase(res.getStatus());
        System.out.println(ANSI_CYAN + "-- Result Received -------------------------------------" + ANSI_RESET);
        System.out.println("  Task ID:      " + res.getTaskId());
        System.out.println("  Status:       " + (ok ? ANSI_GREEN : ANSI_RED) + res.getStatus() + ANSI_RESET);
        System.out.println("  Worker ID:    " + res.getWorkerInstanceId());
        System.out.println("  Agent Used:   " + res.getAgentUsed());
        System.out.println("  Duration:     " + res.getDurationMs() + " ms");
        System.out.println(ANSI_CYAN + "-- AI Response -----------------------------------------" + ANSI_RESET);
        if (ok) {
            System.out.println(res.getOutput() != null ? res.getOutput() : "");
        } else {
            System.out.println(ANSI_RED + "Error: " + res.getErrorMessage() + ANSI_RESET);
        }
        System.out.println(ANSI_CYAN + "--------------------------------------------------------" + ANSI_RESET);
    }

    public static void printHelp() {
        System.out.println(ANSI_CYAN + "MKPRO RabbitMQ Test Client" + ANSI_RESET);
        System.out.println("Usage:");
        System.out.println("  mkpro-mq-test \"Your prompt here\"                  Publish task & wait up to 120s for result");
        System.out.println("  mkpro-mq-test --send-only \"Your prompt here\"      Publish task & exit immediately");
        System.out.println("  mkpro-mq-test --listen                            Continuously listen to resultQueue");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --internal            Use built-in internal HTTP queue bridge (http://127.0.0.1:8765)");
        System.out.println("  --internal-port <p>   Internal HTTP bridge port (default: 8765)");
        System.out.println("  --host <host>         RabbitMQ broker host (default: localhost or RABBITMQ_HOST)");
        System.out.println("  --port <port>         RabbitMQ broker port (default: 5672 or RABBITMQ_PORT)");
        System.out.println("  --agent <agent>       Target agent name (default: Coordinator)");
        System.out.println("  --session <sessionId> Session ID for conversation continuity");
        System.out.println("  --timeout <seconds>   Wait timeout in seconds (default: 120)");
        System.out.println("  --help, -h            Show this help message");
    }
}
