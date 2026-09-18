package com.mkpro.plugins;

import java.io.Serializable;

public class FilterConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private int maxTurns = 10;
    private boolean pruneToolOutputs = true;
    private int maxToolPayloadChars = 2000;
    private boolean evictStaleToolChurn = true;
    private boolean pinInitialPrompt = true;
    private boolean pinMemoryEvents = true;

    public FilterConfig() {}

    public FilterConfig(int maxTurns, boolean pruneToolOutputs, int maxToolPayloadChars,
                        boolean evictStaleToolChurn, boolean pinInitialPrompt, boolean pinMemoryEvents) {
        this.maxTurns = maxTurns;
        this.pruneToolOutputs = pruneToolOutputs;
        this.maxToolPayloadChars = maxToolPayloadChars;
        this.evictStaleToolChurn = evictStaleToolChurn;
        this.pinInitialPrompt = pinInitialPrompt;
        this.pinMemoryEvents = pinMemoryEvents;
    }

    public static FilterConfig defaults() {
        return new FilterConfig();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxTurns = 10;
        private boolean pruneToolOutputs = true;
        private int maxToolPayloadChars = 2000;
        private boolean evictStaleToolChurn = true;
        private boolean pinInitialPrompt = true;
        private boolean pinMemoryEvents = true;

        public Builder maxTurns(int maxTurns) { this.maxTurns = maxTurns; return this; }
        public Builder pruneToolOutputs(boolean pruneToolOutputs) { this.pruneToolOutputs = pruneToolOutputs; return this; }
        public Builder maxToolPayloadChars(int maxToolPayloadChars) { this.maxToolPayloadChars = maxToolPayloadChars; return this; }
        public Builder evictStaleToolChurn(boolean evictStaleToolChurn) { this.evictStaleToolChurn = evictStaleToolChurn; return this; }
        public Builder pinInitialPrompt(boolean pinInitialPrompt) { this.pinInitialPrompt = pinInitialPrompt; return this; }
        public Builder pinMemoryEvents(boolean pinMemoryEvents) { this.pinMemoryEvents = pinMemoryEvents; return this; }

        public FilterConfig build() {
            return new FilterConfig(maxTurns, pruneToolOutputs, maxToolPayloadChars,
                    evictStaleToolChurn, pinInitialPrompt, pinMemoryEvents);
        }
    }

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
    public boolean isPruneToolOutputs() { return pruneToolOutputs; }
    public void setPruneToolOutputs(boolean pruneToolOutputs) { this.pruneToolOutputs = pruneToolOutputs; }
    public int getMaxToolPayloadChars() { return maxToolPayloadChars; }
    public void setMaxToolPayloadChars(int maxToolPayloadChars) { this.maxToolPayloadChars = maxToolPayloadChars; }
    public boolean isEvictStaleToolChurn() { return evictStaleToolChurn; }
    public void setEvictStaleToolChurn(boolean evictStaleToolChurn) { this.evictStaleToolChurn = evictStaleToolChurn; }
    public boolean isPinInitialPrompt() { return pinInitialPrompt; }
    public void setPinInitialPrompt(boolean pinInitialPrompt) { this.pinInitialPrompt = pinInitialPrompt; }
    public boolean isPinMemoryEvents() { return pinMemoryEvents; }
    public void setPinMemoryEvents(boolean pinMemoryEvents) { this.pinMemoryEvents = pinMemoryEvents; }
}
