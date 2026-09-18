package com.mkpro.maker.distillation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Maintains the full execution trajectory recorded for an agent goal attempt.
 */
public class ExecutionTrajectory {
    private String goalId;
    private String initialHypothesis;
    private List<TrajectoryStep> steps;
    private boolean success;
    private String lastError;

    public ExecutionTrajectory() {
        this.steps = new ArrayList<>();
    }

    public ExecutionTrajectory(String goalId, String initialHypothesis) {
        this.goalId = goalId;
        this.initialHypothesis = initialHypothesis;
        this.steps = new ArrayList<>();
        this.success = false;
    }

    public ExecutionTrajectory(String goalId, String initialHypothesis, List<TrajectoryStep> steps, boolean success) {
        this.goalId = goalId;
        this.initialHypothesis = initialHypothesis;
        this.steps = (steps != null) ? new ArrayList<>(steps) : new ArrayList<>();
        this.success = success;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void addStep(TrajectoryStep step) {
        if (step != null) {
            if (step.getStepIndex() <= 0 && !steps.isEmpty()) {
                step.setStepIndex(steps.size() + 1);
            }
            this.steps.add(step);
        }
    }

    public void addStep(int stepIndex, String agentName, String actionOrTool,
                        String inputPayload, String outputOrError, int exitCode, boolean isError) {
        this.steps.add(new TrajectoryStep(stepIndex, agentName, actionOrTool, inputPayload, outputOrError, exitCode, isError));
    }

    public List<TrajectoryStep> getFailedSteps() {
        return steps.stream()
                .filter(step -> step.isError() || step.getExitCode() != 0)
                .collect(Collectors.toList());
    }

    public String getLastError() {
        if (lastError != null && !lastError.isBlank()) {
            return lastError;
        }
        List<TrajectoryStep> failed = getFailedSteps();
        if (failed.isEmpty()) {
            return null;
        }
        TrajectoryStep lastFailed = failed.get(failed.size() - 1);
        return lastFailed.getOutputOrError();
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getSignature() {
        if (steps == null || steps.isEmpty()) {
            return "<empty>";
        }
        return steps.stream()
                .map(step -> {
                    String agent = (step.getAgentName() != null) ? step.getAgentName() : "unknown";
                    String tool = (step.getActionOrTool() != null) ? step.getActionOrTool() : "none";
                    int exit = step.getExitCode();
                    return agent + ":" + tool + (exit != 0 ? "[err:" + exit + "]" : "");
                })
                .collect(Collectors.joining(" -> "));
    }

    public String getGoalId() {
        return goalId;
    }

    public void setGoalId(String goalId) {
        this.goalId = goalId;
    }

    public String getInitialHypothesis() {
        return initialHypothesis;
    }

    public void setInitialHypothesis(String initialHypothesis) {
        this.initialHypothesis = initialHypothesis;
    }

    public List<TrajectoryStep> getSteps() {
        return steps;
    }

    public void setSteps(List<TrajectoryStep> steps) {
        this.steps = (steps != null) ? new ArrayList<>(steps) : new ArrayList<>();
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutionTrajectory that = (ExecutionTrajectory) o;
        return success == that.success &&
                Objects.equals(goalId, that.goalId) &&
                Objects.equals(initialHypothesis, that.initialHypothesis) &&
                Objects.equals(steps, that.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(goalId, initialHypothesis, steps, success);
    }

    @Override
    public String toString() {
        return "ExecutionTrajectory{" +
                "goalId='" + goalId + '\'' +
                ", initialHypothesis='" + initialHypothesis + '\'' +
                ", stepsCount=" + (steps != null ? steps.size() : 0) +
                ", success=" + success +
                '}';
    }

    public static class Builder {
        private String goalId;
        private String initialHypothesis;
        private List<TrajectoryStep> steps = new ArrayList<>();
        private boolean success;

        public Builder goalId(String goalId) {
            this.goalId = goalId;
            return this;
        }

        public Builder initialHypothesis(String initialHypothesis) {
            this.initialHypothesis = initialHypothesis;
            return this;
        }

        public Builder step(TrajectoryStep step) {
            if (step != null) {
                this.steps.add(step);
            }
            return this;
        }

        public Builder steps(List<TrajectoryStep> steps) {
            if (steps != null) {
                this.steps = new ArrayList<>(steps);
            }
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public ExecutionTrajectory build() {
            return new ExecutionTrajectory(goalId, initialHypothesis, steps, success);
        }
    }
}
