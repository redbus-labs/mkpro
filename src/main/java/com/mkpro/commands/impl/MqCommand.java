package com.mkpro.commands.impl;

import com.mkpro.CentralMemory;
import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.infra.mq.MqConfig;
import com.mkpro.infra.mq.MqResultMessage;
import java.util.List;
import com.mkpro.infra.mq.RabbitMqWorkerService;

import java.util.Arrays;

import static com.mkpro.MkPro.*;

/**
 * /mq command — manage RabbitMQ Task Worker (Pattern A).
 *
 * Usage:
 *   /mq on            - Start RabbitMQ task worker
 *   /mq off           - Stop RabbitMQ task worker
 *   /mq status        - Show worker status and queue metrics
 *   /mq config [...]  - Show or update RabbitMQ configuration
 *   /mq send <prompt> - Enqueue a task prompt to RabbitMQ
 */
public class MqCommand implements Command {

    @Override
    public String getName() {
        return "mq";
    }

    @Override
    public String getDescription() {
        return "Manage MQ task worker. Usage: /mq [on [internal|rabbitmq|auto]|off|status|results [limit]|config|send <prompt>]";
    }

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        if (args == null || args.length == 0) {
            showStatus(context);
            return;
        }

        String sub = args[0].trim().toLowerCase();
        switch (sub) {
            case "on" -> startWorker(args, context);
            case "off" -> stopWorker(context);
            case "status" -> showStatus(context);
            case "results" -> showResults(args, context);
            case "config" -> handleConfig(args, context);
            case "send" -> handleSend(args, context);
            default -> showStatus(context);
        }
    }

    private void startWorker(String[] args, MkProContext context) {
        RabbitMqWorkerService existing = context.getRabbitMqWorkerService();
        if (existing != null && existing.isRunning()) {
            if (args.length > 1) {
                existing.stop();
                context.setRabbitMqWorkerService(null);
            } else {
                System.out.println(ANSI_YELLOW + "[MQ] Worker is already running (mode=" + existing.getActiveMode() + ", queue='" + existing.getConfig().getTaskQueue() + "')." + ANSI_RESET);
                return;
            }
        }
        CentralMemory cm = context.getCentralMemory() != null ? context.getCentralMemory() : CentralMemory.getInstance();
        MqConfig cfg = MqConfig.fromEnvAndMemory(cm);
        if (args.length > 1 && args[1] != null && !args[1].isBlank()) {
            cfg.setMode(args[1].trim().toUpperCase());
            cfg.saveToMemory(cm);
        }
        try {
            RabbitMqWorkerService worker = new RabbitMqWorkerService(cfg, context);
            worker.start();
            context.setRabbitMqWorkerService(worker);
            if ("INTERNAL".equalsIgnoreCase(worker.getActiveMode())) {
                System.out.println(ANSI_GREEN + "[MQ] Internal Queue Worker started (bridge=http://127.0.0.1:" + worker.getBoundInternalHttpPort() + ", queue=" + cfg.getTaskQueue() + ")" + ANSI_RESET);
            } else {
                System.out.println(ANSI_GREEN + "[MQ] RabbitMQ Worker started (" + cfg.getHost() + ":" + cfg.getPort() + ", queue=" + cfg.getTaskQueue() + ")" + ANSI_RESET);
            }
        } catch (Exception e) {
            System.out.println(ANSI_RED + "[MQ] Failed to start worker: " + e.getMessage() + ANSI_RESET);
        }
    }

    private void stopWorker(MkProContext context) {
        RabbitMqWorkerService existing = context.getRabbitMqWorkerService();
        if (existing == null || !existing.isRunning()) {
            System.out.println(ANSI_YELLOW + "[MQ] Worker is not running." + ANSI_RESET);
            return;
        }
        existing.stop();
        context.setRabbitMqWorkerService(null);
        System.out.println(ANSI_GREEN + "[MQ] RabbitMQ Worker stopped." + ANSI_RESET);
    }

    private void showStatus(MkProContext context) {
        CentralMemory cm = context.getCentralMemory() != null ? context.getCentralMemory() : CentralMemory.getInstance();
        RabbitMqWorkerService worker = context.getRabbitMqWorkerService();
        MqConfig cfg = (worker != null && worker.getConfig() != null) ? worker.getConfig() : MqConfig.fromEnvAndMemory(cm);

        System.out.println(ANSI_CYAN + "-- RabbitMQ Task Worker Status --" + ANSI_RESET);
        boolean running = worker != null && worker.isRunning();
        String activeMode = (worker != null && running) ? worker.getActiveMode() : "NONE";
        System.out.println("  Status:       " + (running ? ANSI_GREEN + "ONLINE" : ANSI_YELLOW + "OFFLINE") + ANSI_RESET);
        System.out.println("  Active Mode:  " + activeMode + " (configured=" + cfg.getMode() + ")");
        int bridgePort = worker != null ? worker.getBoundInternalHttpPort() : cfg.getInternalHttpPort();
        System.out.println("  HTTP Bridge:  http://127.0.0.1:" + bridgePort);
        System.out.println("  Broker:       " + cfg.getUsername() + "@" + cfg.getHost() + ":" + cfg.getPort() + " (" + cfg.getVirtualHost() + ")");
        System.out.println("  Task Queue:   " + cfg.getTaskQueue());
        System.out.println("  Result Queue: " + cfg.getResultQueue());
        System.out.println("  DLQ Queue:    " + cfg.getDlqQueue());
        if (worker != null) {
            System.out.println("  Worker ID:    " + worker.getWorkerInstanceId());
            System.out.println("  Queue Depths: pending=" + worker.getInternalPendingCount() + ", results=" + worker.getInternalResultCount() + ", dlq=" + worker.getInternalDlqCount());
            System.out.println("  Processed:    " + worker.getTasksProcessed() + " (succeeded=" + worker.getTasksSucceeded() + ", failed=" + worker.getTasksFailed() + ", active=" + worker.getActiveTasks() + ")");
        }
        System.out.println();
    }

    private void showResults(String[] args, MkProContext context) {
        RabbitMqWorkerService worker = context.getRabbitMqWorkerService();
        if (worker == null) {
            System.out.println(ANSI_YELLOW + "[MQ] Worker is not initialized." + ANSI_RESET);
            return;
        }
        int limit = 10;
        if (args.length > 1) {
            try { limit = Integer.parseInt(args[1].trim()); } catch (NumberFormatException ignored) {}
        }
        List<MqResultMessage> results = worker.getRecentResults(limit);
        System.out.println(ANSI_CYAN + "-- Recent MQ Results (" + results.size() + ") --" + ANSI_RESET);
        if (results.isEmpty()) {
            System.out.println(ANSI_DIM + "  (No results recorded in internal queue)" + ANSI_RESET);
            return;
        }
        for (MqResultMessage r : results) {
            boolean ok = "SUCCESS".equalsIgnoreCase(r.getStatus());
            System.out.println("  [" + (ok ? ANSI_GREEN : ANSI_RED) + r.getStatus() + ANSI_RESET + "] taskId=" + r.getTaskId() + " agent=" + r.getAgentUsed() + " (" + r.getDurationMs() + "ms)");
            String txt = ok ? r.getOutput() : r.getErrorMessage();
            if (txt != null && !txt.isBlank()) {
                System.out.println("    " + txt.replace("\n", " "));
            }
        }
    }

    private void handleConfig(String[] args, MkProContext context) {
        CentralMemory cm = context.getCentralMemory() != null ? context.getCentralMemory() : CentralMemory.getInstance();
        MqConfig cfg = MqConfig.fromEnvAndMemory(cm);

        if (args.length == 1) {
            System.out.println(ANSI_CYAN + "-- RabbitMQ Configuration --" + ANSI_RESET);
            System.out.println("  Host:         " + cfg.getHost());
            System.out.println("  Port:         " + cfg.getPort());
            System.out.println("  Username:     " + cfg.getUsername());
            System.out.println("  Virtual Host: " + cfg.getVirtualHost());
            System.out.println("  Task Queue:   " + cfg.getTaskQueue());
            System.out.println("  Result Queue: " + cfg.getResultQueue());
            System.out.println("  DLQ Queue:    " + cfg.getDlqQueue());
            System.out.println("  Prefetch:     " + cfg.getPrefetchCount());
            System.out.println("  Timeout (s):  " + cfg.getTaskTimeoutSeconds());
            System.out.println(ANSI_DIM + "  Usage: /mq config <host> [port] [user] [pass] [taskQueue] [resultQueue]" + ANSI_RESET);
            return;
        }

        cfg.setHost(args[1]);
        if (args.length > 2) {
            try { cfg.setPort(Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
        }
        if (args.length > 3) cfg.setUsername(args[3]);
        if (args.length > 4) cfg.setPassword(args[4]);
        if (args.length > 5) cfg.setTaskQueue(args[5]);
        if (args.length > 6) cfg.setResultQueue(args[6]);

        cfg.saveToMemory(cm);
        System.out.println(ANSI_GREEN + "[MQ] Saved configuration: " + cfg.getUsername() + "@" + cfg.getHost() + ":" + cfg.getPort() + " (queue=" + cfg.getTaskQueue() + ")" + ANSI_RESET);
    }

    private void handleSend(String[] args, MkProContext context) {
        if (args.length < 2) {
            System.out.println(ANSI_YELLOW + "Usage: /mq send <prompt>" + ANSI_RESET);
            return;
        }
        String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (prompt.isEmpty()) {
            System.out.println(ANSI_YELLOW + "Usage: /mq send <prompt>" + ANSI_RESET);
            return;
        }
        CentralMemory cm = context.getCentralMemory() != null ? context.getCentralMemory() : CentralMemory.getInstance();
        RabbitMqWorkerService service = context.getRabbitMqWorkerService();
        if (service == null) {
            service = new RabbitMqWorkerService(MqConfig.fromEnvAndMemory(cm), context);
            try { service.start(); context.setRabbitMqWorkerService(service); } catch (Exception ignored) {}
        }
        try {
            String taskId = service.publishTask(prompt, "Coordinator", null);
            System.out.println(ANSI_GREEN + "[MQ] Task published (taskId=" + taskId + ", queue=" + service.getConfig().getTaskQueue() + ")" + ANSI_RESET);
        } catch (Exception e) {
            System.out.println(ANSI_RED + "[MQ] Failed to publish task: " + e.getMessage() + ANSI_RESET);
        }
    }
}
