package com.mkpro.infra.mq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RabbitMqWorkerServiceTest {
    static { System.setProperty("net.bytebuddy.experimental", "true"); }

    private MqConfig config;
    private ConnectionFactory mockFactory;
    private Connection mockConnection;
    private Channel mockChannel;

    @BeforeEach
    void setUp() throws Exception {
        config = new MqConfig("localhost", 5672, "guest", "guest", "mkpro.tasks", "mkpro.results");
        mockConnection = mock(Connection.class);
        mockChannel = mock(Channel.class);
        mockFactory = new ConnectionFactory() {
            @Override
            public Connection newConnection(String connectionName) { return mockConnection; }
            @Override
            public Connection newConnection() { return mockConnection; }
        };
        when(mockConnection.createChannel()).thenReturn(mockChannel);
        when(mockConnection.isOpen()).thenReturn(true);
        when(mockChannel.isOpen()).thenReturn(true);
        when(mockChannel.basicConsume(anyString(), anyBoolean(), any(DeliverCallback.class), any(CancelCallback.class)))
                .thenReturn("ctag-1");
    }

    @Test
    void taskMessageJsonRoundTripAndPlainTextFallback() throws Exception {
        MqTaskMessage original = new MqTaskMessage("task-101", "Analyze repo", "Architect");
        original.setCorrelationId("corr-101");
        original.setReplyTo("custom.replies");

        String json = original.toJson();
        MqTaskMessage parsed = MqTaskMessage.fromJson(json);
        assertEquals("task-101", parsed.getTaskId());
        assertEquals("Analyze repo", parsed.getPrompt());
        assertEquals("Architect", parsed.getTargetAgent());
        assertEquals("corr-101", parsed.getCorrelationId());
        assertEquals("custom.replies", parsed.getReplyTo());

        MqTaskMessage plain = MqTaskMessage.fromJson("Hello world prompt");
        assertEquals("Hello world prompt", plain.getPrompt());
        assertEquals("Coordinator", plain.getTargetAgent());
        assertNotNull(plain.getTaskId());
    }

    @Test
    void startAndStopLifecycleDeclaresTopology() throws Exception {
        RabbitMqWorkerService service = new RabbitMqWorkerService(
                config, mockFactory, task -> "ok", "worker-test-1");

        assertFalse(service.isRunning());
        service.start();
        assertTrue(service.isRunning());
        assertEquals("ctag-1", service.getConsumerTag());

        verify(mockChannel).queueDeclare(eq("mkpro.tasks.dlq"), eq(true), eq(false), eq(false), isNull());
        verify(mockChannel).queueDeclare(eq("mkpro.tasks"), eq(true), eq(false), eq(false), anyMap());
        verify(mockChannel).queueDeclare(eq("mkpro.results"), eq(true), eq(false), eq(false), isNull());
        verify(mockChannel).basicQos(1);

        service.stop();
        assertFalse(service.isRunning());
        verify(mockChannel).basicCancel("ctag-1");
        verify(mockChannel).close();
        verify(mockConnection).close();
    }

    @Test
    void handleDeliverySuccessPublishesResultAndAcks() throws Exception {
        RabbitMqWorkerService service = new RabbitMqWorkerService(
                config, mockFactory, task -> "Processed: " + task.getPrompt(), "worker-1");
        service.start();

        MqTaskMessage task = new MqTaskMessage("t-200", "Run audit", "Coordinator");
        Envelope env = new Envelope(42L, false, "", "mkpro.tasks");
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().correlationId("c-200").build();
        Delivery delivery = new Delivery(env, props, task.toJson().getBytes(StandardCharsets.UTF_8));

        service.handleDelivery("ctag-1", delivery);

        assertEquals(1, service.getTasksProcessed());
        assertEquals(1, service.getTasksSucceeded());
        assertEquals(0, service.getTasksFailed());

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockChannel).basicPublish(eq(""), eq("mkpro.results"), any(AMQP.BasicProperties.class), bodyCaptor.capture());
        verify(mockChannel).basicAck(42L, false);

        MqResultMessage result = MqResultMessage.fromJson(new String(bodyCaptor.getValue(), StandardCharsets.UTF_8));
        assertEquals("t-200", result.getTaskId());
        assertEquals("c-200", result.getCorrelationId());
        assertEquals("SUCCESS", result.getStatus());
        assertEquals("Processed: Run audit", result.getOutput());
        assertEquals("worker-1", result.getWorkerInstanceId());
    }

    @Test
    void handleDeliveryErrorPublishesErrorResultAndAcks() throws Exception {
        RabbitMqWorkerService service = new RabbitMqWorkerService(
                config, mockFactory, task -> { throw new RuntimeException("LLM provider down"); }, "worker-2");
        service.start();

        MqTaskMessage task = new MqTaskMessage("t-300", "Fail task", "Coordinator");
        Envelope env = new Envelope(55L, false, "", "mkpro.tasks");
        Delivery delivery = new Delivery(env, new AMQP.BasicProperties(), task.toJson().getBytes(StandardCharsets.UTF_8));

        service.handleDelivery("ctag-1", delivery);

        assertEquals(1, service.getTasksProcessed());
        assertEquals(0, service.getTasksSucceeded());
        assertEquals(1, service.getTasksFailed());

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockChannel).basicPublish(eq(""), eq("mkpro.results"), any(AMQP.BasicProperties.class), bodyCaptor.capture());
        verify(mockChannel).basicAck(55L, false);

        MqResultMessage result = MqResultMessage.fromJson(new String(bodyCaptor.getValue(), StandardCharsets.UTF_8));
        assertEquals("ERROR", result.getStatus());
        assertEquals("LLM provider down", result.getErrorMessage());
    }

    @Test
    void handleDeliveryPoisonPayloadNacksToDlq() throws Exception {
        RabbitMqWorkerService service = new RabbitMqWorkerService(
                config, mockFactory, task -> "ok", "worker-3");
        service.start();

        Envelope env = new Envelope(99L, false, "", "mkpro.tasks");
        Delivery delivery = new Delivery(env, new AMQP.BasicProperties(), "{bad json".getBytes(StandardCharsets.UTF_8));

        service.handleDelivery("ctag-1", delivery);

        assertEquals(1, service.getTasksProcessed());
        assertEquals(1, service.getTasksFailed());
        verify(mockChannel).basicNack(99L, false, false);
    }

    @Test
    void publishTaskSendsToTaskQueue() throws Exception {
        RabbitMqWorkerService service = new RabbitMqWorkerService(
                config, mockFactory, task -> "ok", "worker-4");
        service.start();

        String taskId = service.publishTask("Build feature X", "Coder", "custom.results");
        assertNotNull(taskId);

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockChannel).basicPublish(eq(""), eq("mkpro.tasks"), any(AMQP.BasicProperties.class), bodyCaptor.capture());
        MqTaskMessage published = MqTaskMessage.fromJson(new String(bodyCaptor.getValue(), StandardCharsets.UTF_8));
        assertEquals(taskId, published.getTaskId());
        assertEquals("Build feature X", published.getPrompt());
        assertEquals("Coder", published.getTargetAgent());
        assertEquals("custom.results", published.getReplyTo());
    }

    @Test
    void internalModeProcessesTasksAndStoresResults() throws Exception {
        config.setMode("INTERNAL");
        config.setInternalHttpPort(0);
        RabbitMqWorkerService service = new RabbitMqWorkerService(config, mockFactory, task -> "Internal: " + task.getPrompt(), "worker-int-1");
        try {
            service.start();
            assertTrue(service.isRunning());
            assertEquals("INTERNAL", service.getActiveMode());

            String taskId = service.publishTask("Check status", "Coordinator", null);
            MqResultMessage res = service.pollResult(taskId, 5000L);
            assertNotNull(res);
            assertEquals("SUCCESS", res.getStatus());
            assertEquals("Internal: Check status", res.getOutput());
            assertEquals(1, service.getInternalResultCount());
            List<MqResultMessage> recent = service.getRecentResults(5);
            assertEquals(1, recent.size());
            assertEquals(taskId, recent.get(0).getTaskId());

            service.processInternalTask(new MqTaskMessage("bad-1", "   ", "Coordinator"));
            assertEquals(1, service.getInternalDlqCount());
        } finally {
            service.stop();
        }
    }

    @Test
    void autoModeFallsBackToInternalWhenRabbitMqUnreachable() throws Exception {
        config.setMode("AUTO");
        ConnectionFactory failingFactory = new ConnectionFactory() {
            @Override
            public Connection newConnection(String name) throws IOException {
                throw new IOException("Connection refused");
            }
            @Override
            public Connection newConnection() throws IOException {
                throw new IOException("Connection refused");
            }
        };
        RabbitMqWorkerService service = new RabbitMqWorkerService(config, failingFactory, task -> "AutoFallback: " + task.getPrompt(), "worker-auto-1");
        try {
            service.start();
            assertTrue(service.isRunning());
            assertEquals("INTERNAL", service.getActiveMode());

            String taskId = service.publishTask("Hello fallback", "Coordinator", null);
            MqResultMessage res = service.pollResult(taskId, 5000L);
            assertNotNull(res);
            assertEquals("SUCCESS", res.getStatus());
            assertEquals("AutoFallback: Hello fallback", res.getOutput());

            int port = service.getBoundInternalHttpPort();
            HttpClient client = HttpClient.newHttpClient();
            MqTaskMessage httpTask = new MqTaskMessage("http-t1", "Via HTTP", "Coordinator");
            HttpRequest postReq = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/mq/tasks")).POST(HttpRequest.BodyPublishers.ofString(httpTask.toJson())).build();
            HttpResponse<String> postResp = client.send(postReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, postResp.statusCode());

            HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/mq/results?taskId=http-t1&timeout=5")).GET().build();
            HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, getResp.statusCode());
            MqResultMessage httpRes = MqResultMessage.fromJson(getResp.body());
            assertEquals("AutoFallback: Via HTTP", httpRes.getOutput());
        } finally {
            service.stop();
        }
    }
}
