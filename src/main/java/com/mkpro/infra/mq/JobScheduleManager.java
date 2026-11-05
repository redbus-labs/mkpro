package com.mkpro.infra.mq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mkpro.CentralMemory;
import com.mkpro.core.MkProContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages scheduled and event-driven background jobs dispatched to RabbitMqWorkerService.
 * Persists job schedules per-project in CentralMemory.
 */
public class JobScheduleManager {

    private static final long EDIT_DEBOUNCE_MS = 15_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JobScheduleManager INSTANCE = new JobScheduleManager();

    public static JobScheduleManager getInstance() {
        return INSTANCE;
    }

    public static class ScheduledJob {
        public String id;
        public String name;
        public String prompt;
        public String targetAgent;
        public int intervalSeconds;
        public boolean triggerOnEdit;
        public boolean enabled;
        public long createdAt;
        public long lastRunAt;
        public int runCount;

        public ScheduledJob() {
        }

        public ScheduledJob(String id, String name, String prompt, String targetAgent,
                            int intervalSeconds, boolean triggerOnEdit, boolean enabled,
                            long createdAt, long lastRunAt, int runCount) {
            this.id = id;
            this.name = name;
            this.prompt = prompt;
            this.targetAgent = targetAgent;
            this.intervalSeconds = intervalSeconds;
            this.triggerOnEdit = triggerOnEdit;
            this.enabled = enabled;
            this.createdAt = createdAt;
            this.lastRunAt = lastRunAt;
            this.runCount = runCount;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }

        public String getTargetAgent() { return targetAgent; }
        public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }

        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }

        public boolean isTriggerOnEdit() { return triggerOnEdit; }
        public void setTriggerOnEdit(boolean triggerOnEdit) { this.triggerOnEdit = triggerOnEdit; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

        public long getLastRunAt() { return lastRunAt; }
        public void setLastRunAt(long lastRunAt) { this.lastRunAt = lastRunAt; }

        public int getRunCount() { return runCount; }
        public void setRunCount(int runCount) { this.runCount = runCount; }
    }

    private final Map<String, ScheduledJob> schedules = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Long> lastEditTriggerTimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker;
    private volatile CentralMemory centralMemory;
    private volatile MkProContext mkProContext;

    private JobScheduleManager() {
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkpro-job-schedule-ticker");
            t.setDaemon(true);
            return t;
        });
        this.ticker.scheduleAtFixedRate(this::checkIntervalSchedules, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Binds CentralMemory and MkProContext so schedules are loaded/persisted and workers can be auto-started.
     */
    public synchronized void init(CentralMemory memory, MkProContext context) {
        this.centralMemory = memory;
        this.mkProContext = context;
        loadFromMemory();
    }

    private static String getStorageKey() {
        return CentralMemory.getCurrentProjectKeyPrefix() + "mq:schedules";
    }

    public synchronized void loadFromMemory() {
        CentralMemory cm = this.centralMemory;
        if (cm == null) return;
        try {
            String raw = cm.getMemory(getStorageKey());
            if (raw != null && !raw.isBlank()) {
                List<ScheduledJob> loaded = MAPPER.readValue(raw, new TypeReference<List<ScheduledJob>>() {});
                if (loaded != null) {
                    schedules.clear();
                    for (ScheduledJob job : loaded) {
                        if (job != null && job.id != null) {
                            schedules.put(job.id, job);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void persistToMemory() {
        CentralMemory cm = this.centralMemory;
        if (cm == null) return;
        try {
            String json = MAPPER.writeValueAsString(new ArrayList<>(schedules.values()));
            cm.saveMemory(getStorageKey(), json);
        } catch (Exception ignored) {
        }
    }

    private void checkIntervalSchedules() {
        long now = System.currentTimeMillis();
        List<ScheduledJob> snapshot = listSchedules();
        for (ScheduledJob job : snapshot) {
            if (!job.enabled || job.intervalSeconds <= 0) {
                continue;
            }
            long baseline = job.lastRunAt > 0 ? job.lastRunAt : job.createdAt;
            if (now - baseline >= job.intervalSeconds * 1000L) {
                triggerNow(job.id);
            }
        }
    }

    /**
     * Adds a new scheduled and/or event-triggered background job.
     */
    public synchronized ScheduledJob addSchedule(String name, String prompt, String targetAgent,
                                                 int intervalSeconds, boolean triggerOnEdit) {
        String id = "sched-" + UUID.randomUUID().toString().substring(0, 8);
        String resolvedName = (name != null && !name.isBlank()) ? name.trim() : "Scheduled Job " + (schedules.size() + 1);
        String resolvedPrompt = prompt != null ? prompt.trim() : "";
        String resolvedAgent = (targetAgent != null && !targetAgent.isBlank()) ? targetAgent.trim() : "Coordinator";
        int resolvedInterval = Math.max(0, intervalSeconds);

        ScheduledJob job = new ScheduledJob(
            id,
            resolvedName,
            resolvedPrompt,
            resolvedAgent,
            resolvedInterval,
            triggerOnEdit,
            true,
            System.currentTimeMillis(),
            0L,
            0
        );
        schedules.put(id, job);
        persistToMemory();
        return job;
    }

    /**
     * Lists all registered job schedules in creation order.
     */
    public synchronized List<ScheduledJob> listSchedules() {
        return new ArrayList<>(schedules.values());
    }

    /**
     * Retrieves a single schedule by ID, or null if not found.
     */
    public synchronized ScheduledJob getSchedule(String id) {
        if (id == null) return null;
        return schedules.get(id);
    }

    /**
     * Sets the enabled flag of a schedule.
     */
    public synchronized boolean toggleSchedule(String id, boolean enabled) {
        if (id == null) return false;
        ScheduledJob job = schedules.get(id);
        if (job == null) return false;
        job.enabled = enabled;
        persistToMemory();
        return true;
    }

    /**
     * Flips the enabled flag of a schedule.
     */
    public synchronized boolean toggleSchedule(String id) {
        if (id == null) return false;
        ScheduledJob job = schedules.get(id);
        if (job == null) return false;
        job.enabled = !job.enabled;
        persistToMemory();
        return true;
    }

    /**
     * Removes a schedule by ID.
     */
    public synchronized boolean removeSchedule(String id) {
        if (id == null) return false;
        lastEditTriggerTimes.remove(id);
        boolean removed = schedules.remove(id) != null;
        if (removed) {
            persistToMemory();
        }
        return removed;
    }

    /**
     * Clears all schedules (useful for testing).
     */
    public synchronized void clearSchedules() {
        schedules.clear();
        lastEditTriggerTimes.clear();
        persistToMemory();
    }

    /**
     * Immediately dispatches the job to RabbitMqWorkerService (if available) and increments runCount + updates lastRunAt.
     */
    public synchronized boolean triggerNow(String id) {
        return triggerNow(id, null);
    }

    private synchronized boolean triggerNow(String id, String editFilePath) {
        if (id == null) return false;
        ScheduledJob job = schedules.get(id);
        if (job == null) return false;

        job.runCount++;
        job.lastRunAt = System.currentTimeMillis();
        persistToMemory();

        String dispatchPrompt = job.prompt != null ? job.prompt : "";
        if (editFilePath != null && !editFilePath.isBlank()) {
            dispatchPrompt = dispatchPrompt + " [Triggered by file edit: " + editFilePath + "]";
        }

        if (!dispatchPrompt.isBlank()) {
            RabbitMqWorkerService worker = resolveOrStartWorker();
            if (worker != null) {
                String agent = (job.targetAgent != null && !job.targetAgent.isBlank()) ? job.targetAgent : "Coordinator";
                try {
                    if (worker.isRunning()) {
                        worker.publishTask(dispatchPrompt, agent, null);
                    } else {
                        worker.sendLocalTask(dispatchPrompt, agent);
                    }
                } catch (Exception ignored) {
                    try {
                        worker.sendLocalTask(dispatchPrompt, agent);
                    } catch (Exception ignored2) {
                    }
                }
            }
        }
        return true;
    }

    private RabbitMqWorkerService resolveOrStartWorker() {
        RabbitMqWorkerService worker = RabbitMqWorkerService.getInstance();
        if (worker == null && mkProContext != null) {
            worker = mkProContext.getRabbitMqWorkerService();
        }
        if (worker == null && mkProContext != null) {
            try {
                CentralMemory cm = centralMemory != null ? centralMemory : mkProContext.getCentralMemory();
                MqConfig cfg = MqConfig.fromEnvAndMemory(cm);
                worker = new RabbitMqWorkerService(cfg, mkProContext);
                worker.start();
                mkProContext.setRabbitMqWorkerService(worker);
            } catch (Exception ignored) {
            }
        } else if (worker != null && !worker.isRunning() && mkProContext != null) {
            try {
                worker.start();
            } catch (Exception ignored) {
            }
        }
        return worker;
    }

    /**
     * Notifies the manager that a file was edited.
     * Checks enabled schedules with triggerOnEdit == true (with a 15-second debounce cooldown) and triggers them.
     */
    public synchronized int notifyFileEdited(String filePath) {
        long now = System.currentTimeMillis();
        int triggeredCount = 0;
        List<ScheduledJob> snapshot = listSchedules();
        for (ScheduledJob job : snapshot) {
            if (!job.enabled || !job.triggerOnEdit) {
                continue;
            }
            Long lastTrigger = lastEditTriggerTimes.get(job.id);
            if (lastTrigger != null && (now - lastTrigger) < EDIT_DEBOUNCE_MS) {
                continue;
            }
            lastEditTriggerTimes.put(job.id, now);
            if (triggerNow(job.id, filePath)) {
                triggeredCount++;
            }
        }
        return triggeredCount;
    }
}
