package com.mkpro.maker.distillation;

import java.util.Objects;

/**
 * Represents a discrete execution step in an agent trajectory.
 */
public class TrajectoryStep {
    private int stepIndex;
    private String agentName;
    private String actionOrTool;
    private String inputPayload;
    private String outputOrError;
    private int exitCode;
    private boolean isError;
    private long timestamp;

    public TrajectoryStep() {
        this.timestamp = System.currentTimeMillis();
    }

    public TrajectoryStep(int stepIndex, String agentName, String actionOrTool,
                          String inputPayload, String outputOrError, int exitCode,
                          boolean isError, long timestamp) {
        this.stepIndex = stepIndex;
        this.agentName = agentName;
        this.actionOrTool = actionOrTool;
        this.inputPayload = inputPayload;
        this.outputOrError = outputOrError;
        this.exitCode = exitCode;
        this.isError = isError;
        this.timestamp = timestamp;
    }

    public TrajectoryStep(int stepIndex, String agentName, String actionOrTool,
                          String inputPayload, String outputOrError, int exitCode,
                          boolean isError) {
        this(stepIndex, agentName, actionOrTool, inputPayload, outputOrError, exitCode, isError, System.currentTimeMillis());
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getActionOrTool() {
        return actionOrTool;
    }

    public void setActionOrTool(String actionOrTool) {
        this.actionOrTool = actionOrTool;
    }

    public String getInputPayload() {
        return inputPayload;
    }

    public void setInputPayload(String inputPayload) {
        this.inputPayload = inputPayload;
    }

    public String getOutputOrError() {
        return outputOrError;
    }

    public void setOutputOrError(String outputOrError) {
        this.outputOrError = outputOrError;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public boolean isError() {
        return isError;
    }

    public void setError(boolean error) {
        isError = error;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrajectoryStep that = (TrajectoryStep) o;
        return stepIndex == that.stepIndex &&
                exitCode == that.exitCode &&
                isError == that.isError &&
                timestamp == that.timestamp &&
                Objects.equals(agentName, that.agentName) &&
                Objects.equals(actionOrTool, that.actionOrTool) &&
                Objects.equals(inputPayload, that.inputPayload) &&
                Objects.equals(outputOrError, that.outputOrError);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stepIndex, agentName, actionOrTool, inputPayload, outputOrError, exitCode, isError, timestamp);
    }

    @Override
    public String toString() {
        return "TrajectoryStep{" +
                "stepIndex=" + stepIndex +
                ", agentName='" + agentName + '\'' +
                ", actionOrTool='" + actionOrTool + '\'' +
                ", exitCode=" + exitCode +
                ", isError=" + isError +
                ", timestamp=" + timestamp +
                '}';
    }

    public static class Builder {
        private int stepIndex;
        private String agentName;
        private String actionOrTool;
        private String inputPayload;
        private String outputOrError;
        private int exitCode;
        private boolean isError;
        private long timestamp = System.currentTimeMillis();

        public Builder stepIndex(int stepIndex) {
            this.stepIndex = stepIndex;
            return this;
        }

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder actionOrTool(String actionOrTool) {
            this.actionOrTool = actionOrTool;
            return this;
        }

        public Builder inputPayload(String inputPayload) {
            this.inputPayload = inputPayload;
            return this;
        }

        public Builder outputOrError(String outputOrError) {
            this.outputOrError = outputOrError;
            return this;
        }

        public Builder exitCode(int exitCode) {
            this.exitCode = exitCode;
            return this;
        }

        public Builder isError(boolean isError) {
            this.isError = isError;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public TrajectoryStep build() {
            return new TrajectoryStep(stepIndex, agentName, actionOrTool, inputPayload, outputOrError, exitCode, isError, timestamp);
        }
    }
}
