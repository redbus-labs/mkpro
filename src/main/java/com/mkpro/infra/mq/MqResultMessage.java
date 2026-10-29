package com.mkpro.infra.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MqResultMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String taskId;
    private String correlationId;
    private String status;
    private String output;
    private String errorMessage;
    private String workerInstanceId;
    private String agentUsed;
    private long durationMs;
    private long completedAt = System.currentTimeMillis();

    public MqResultMessage() {}

    public static MqResultMessage success(String taskId, String correlationId, String output, String workerInstanceId, String agentUsed, long durationMs) {
        MqResultMessage msg = new MqResultMessage();
        msg.setTaskId(taskId);
        msg.setCorrelationId(correlationId != null ? correlationId : taskId);
        msg.setStatus("SUCCESS");
        msg.setOutput(output);
        msg.setWorkerInstanceId(workerInstanceId);
        msg.setAgentUsed(agentUsed);
        msg.setDurationMs(durationMs);
        msg.setCompletedAt(System.currentTimeMillis());
        return msg;
    }

    public static MqResultMessage error(String taskId, String correlationId, String errorMessage, String workerInstanceId, String agentUsed, long durationMs) {
        MqResultMessage msg = new MqResultMessage();
        msg.setTaskId(taskId);
        msg.setCorrelationId(correlationId != null ? correlationId : taskId);
        msg.setStatus("ERROR");
        msg.setErrorMessage(errorMessage);
        msg.setWorkerInstanceId(workerInstanceId);
        msg.setAgentUsed(agentUsed);
        msg.setDurationMs(durationMs);
        msg.setCompletedAt(System.currentTimeMillis());
        return msg;
    }

    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    public static MqResultMessage fromJson(String json) throws Exception {
        return MAPPER.readValue(json, MqResultMessage.class);
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getWorkerInstanceId() { return workerInstanceId; }
    public void setWorkerInstanceId(String workerInstanceId) { this.workerInstanceId = workerInstanceId; }
    public String getAgentUsed() { return agentUsed; }
    public void setAgentUsed(String agentUsed) { this.agentUsed = agentUsed; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
}
