package com.mkpro.plugins;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicBoolean;

public class FilterConfig implements Serializable {
    private static final long serialVersionUID = 2L;

    private int maxTurns = 10;
    private boolean pruneToolOutputs = true;
    private int maxToolPayloadChars = 2000;
    private boolean evictStaleToolChurn = true;
    private boolean pinInitialPrompt = true;
    private boolean pinMemoryEvents = true;

    // Strategy flags for Prefix Cache preservation
    private boolean slidingWindowEnabled = false;
    private boolean sessionStartFilterOnly = true;
    private AtomicBoolean manualCompactionRequested = new AtomicBoolean(false);

    public FilterConfig() {}

    public FilterConfig(int maxTurns, boolean pruneToolOutputs, int maxToolPayloadChars,
                        boolean evictStaleToolChurn, boolean pinInitialPrompt, boolean pinMemoryEvents) {
        this.maxTurns = maxTurns;
        this.pruneToolOutputs = pruneToolOutputs;
        this.maxToolPayloadChars = maxToolPayloadChars;
        this.evictStaleToolChurn = evictStaleToolChurn;
        this.pinInitialPrompt = pinInitialPrompt;
        this.pinMemoryEvents = pinMemoryEvents;
        this.slidingWindowEnabled = false;
        this.sessionStartFilterOnly = true;
    }

    public FilterConfig(int maxTurns, boolean pruneToolOutputs, int maxToolPayloadChars,
                        boolean evictStaleToolChurn, boolean pinInitialPrompt, boolean pinMemoryEvents,
                        boolean slidingWindowEnabled, boolean sessionStartFilterOnly) {
        this(maxTurns, pruneToolOutputs, maxToolPayloadChars, evictStaleToolChurn, pinInitialPrompt, pinMemoryEvents);
        this.slidingWindowEnabled = slidingWindowEnabled;
        this.sessionStartFilterOnly = sessionStartFilterOnly;
    }

    private Object readResolve() {
        if (manualCompactionRequested == null) {
            manualCompactionRequested = new AtomicBoolean(false);
        }
        return this;
    }

    private AtomicBoolean getCompactionFlag() {
        if (manualCompactionRequested == null) {
            manualCompactionRequested = new AtomicBoolean(false);
        }
        return manualCompactionRequested;
    }

    public static FilterConfig defaults() {
        return new FilterConfig();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .maxTurns(this.maxTurns)
                .pruneToolOutputs(this.pruneToolOutputs)
                .maxToolPayloadChars(this.maxToolPayloadChars)
                .evictStaleToolChurn(this.evictStaleToolChurn)
                .pinInitialPrompt(this.pinInitialPrompt)
                .pinMemoryEvents(this.pinMemoryEvents)
                .slidingWindowEnabled(this.slidingWindowEnabled)
                .sessionStartFilterOnly(this.sessionStartFilterOnly)
                .manualCompactionRequested(this.isManualCompactionRequested());
    }

    public static class Builder {
        private int maxTurns = 10;
        private boolean pruneToolOutputs = true;
        private int maxToolPayloadChars = 2000;
        private boolean evictStaleToolChurn = true;
        private boolean pinInitialPrompt = true;
        private boolean pinMemoryEvents = true;
        private boolean slidingWindowEnabled = false;
        private boolean sessionStartFilterOnly = true;
        private boolean manualCompactionRequested = false;

        public Builder maxTurns(int maxTurns) { this.maxTurns = maxTurns; return this; }
        public Builder pruneToolOutputs(boolean pruneToolOutputs) { this.pruneToolOutputs = pruneToolOutputs; return this; }
        public Builder maxToolPayloadChars(int maxToolPayloadChars) { this.maxToolPayloadChars = maxToolPayloadChars; return this; }
        public Builder evictStaleToolChurn(boolean evictStaleToolChurn) { this.evictStaleToolChurn = evictStaleToolChurn; return this; }
        public Builder pinInitialPrompt(boolean pinInitialPrompt) { this.pinInitialPrompt = pinInitialPrompt; return this; }
        public Builder pinMemoryEvents(boolean pinMemoryEvents) { this.pinMemoryEvents = pinMemoryEvents; return this; }
        public Builder slidingWindowEnabled(boolean slidingWindowEnabled) { this.slidingWindowEnabled = slidingWindowEnabled; return this; }
        public Builder sessionStartFilterOnly(boolean sessionStartFilterOnly) { this.sessionStartFilterOnly = sessionStartFilterOnly; return this; }
        public Builder manualCompactionRequested(boolean manualCompactionRequested) { this.manualCompactionRequested = manualCompactionRequested; return this; }

        public FilterConfig build() {
            FilterConfig cfg = new FilterConfig(maxTurns, pruneToolOutputs, maxToolPayloadChars,
                    evictStaleToolChurn, pinInitialPrompt, pinMemoryEvents,
                    slidingWindowEnabled, sessionStartFilterOnly);
            if (manualCompactionRequested) {
                cfg.requestManualCompaction();
            }
            return cfg;
        }
    }

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
    public FilterConfig maxTurns(int maxTurns) { this.maxTurns = maxTurns; return this; }

    public boolean isPruneToolOutputs() { return pruneToolOutputs; }
    public void setPruneToolOutputs(boolean pruneToolOutputs) { this.pruneToolOutputs = pruneToolOutputs; }
    public FilterConfig pruneToolOutputs(boolean pruneToolOutputs) { this.pruneToolOutputs = pruneToolOutputs; return this; }

    public int getMaxToolPayloadChars() { return maxToolPayloadChars; }
    public void setMaxToolPayloadChars(int maxToolPayloadChars) { this.maxToolPayloadChars = maxToolPayloadChars; }
    public FilterConfig maxToolPayloadChars(int maxToolPayloadChars) { this.maxToolPayloadChars = maxToolPayloadChars; return this; }

    public boolean isEvictStaleToolChurn() { return evictStaleToolChurn; }
    public void setEvictStaleToolChurn(boolean evictStaleToolChurn) { this.evictStaleToolChurn = evictStaleToolChurn; }
    public FilterConfig evictStaleToolChurn(boolean evictStaleToolChurn) { this.evictStaleToolChurn = evictStaleToolChurn; return this; }

    public boolean isPinInitialPrompt() { return pinInitialPrompt; }
    public void setPinInitialPrompt(boolean pinInitialPrompt) { this.pinInitialPrompt = pinInitialPrompt; }
    public FilterConfig pinInitialPrompt(boolean pinInitialPrompt) { this.pinInitialPrompt = pinInitialPrompt; return this; }

    public boolean isPinMemoryEvents() { return pinMemoryEvents; }
    public void setPinMemoryEvents(boolean pinMemoryEvents) { this.pinMemoryEvents = pinMemoryEvents; }
    public FilterConfig pinMemoryEvents(boolean pinMemoryEvents) { this.pinMemoryEvents = pinMemoryEvents; return this; }

    public boolean isSlidingWindowEnabled() { return slidingWindowEnabled; }
    public void setSlidingWindowEnabled(boolean slidingWindowEnabled) { this.slidingWindowEnabled = slidingWindowEnabled; }
    public FilterConfig slidingWindowEnabled(boolean slidingWindowEnabled) { this.slidingWindowEnabled = slidingWindowEnabled; return this; }

    public boolean isSessionStartFilterOnly() { return sessionStartFilterOnly; }
    public void setSessionStartFilterOnly(boolean sessionStartFilterOnly) { this.sessionStartFilterOnly = sessionStartFilterOnly; }
    public FilterConfig sessionStartFilterOnly(boolean sessionStartFilterOnly) { this.sessionStartFilterOnly = sessionStartFilterOnly; return this; }

    public boolean isManualCompactionRequested() { return getCompactionFlag().get(); }
    public void setManualCompactionRequested(boolean requested) { getCompactionFlag().set(requested); }
    public FilterConfig manualCompactionRequested(boolean requested) { setManualCompactionRequested(requested); return this; }
    public void requestManualCompaction() { getCompactionFlag().set(true); }
    public AtomicBoolean getManualCompactionRequested() { return getCompactionFlag(); }

    /**
     * Helper method indicating whether the configuration is optimized for LLM KV prefix cache preservation.
     * Prefix caching requires that previous prompt prefixes remain strictly untouched (no dynamic sliding-window
     * truncation during active conversation turns).
     */
    public boolean isPrefixCacheOptimized() {
        return sessionStartFilterOnly && !slidingWindowEnabled;
    }
}
