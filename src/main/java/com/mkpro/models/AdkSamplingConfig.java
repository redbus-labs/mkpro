package com.mkpro.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AdkSamplingConfig {

    @JsonProperty("temperature")
    private double temperature = 0.2;

    @JsonProperty("top_p")
    private double topP = 0.95;

    @JsonProperty("max_output_tokens")
    private int maxOutputTokens = 16384;

    @JsonProperty("thinking_config")
    private ThinkingConfig thinkingConfig;

    public AdkSamplingConfig() {
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public double getTopP() {
        return topP;
    }

    public void setTopP(double topP) {
        this.topP = topP;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public ThinkingConfig getThinkingConfig() {
        return thinkingConfig;
    }

    public void setThinkingConfig(ThinkingConfig thinkingConfig) {
        this.thinkingConfig = thinkingConfig;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ThinkingConfig {
        @JsonProperty("thinking_budget")
        private int thinkingBudget = 4096;

        @JsonProperty("include_thoughts")
        private boolean includeThoughts = true;

        public ThinkingConfig() {
        }

        public int getThinkingBudget() {
            return thinkingBudget;
        }

        public void setThinkingBudget(int thinkingBudget) {
            this.thinkingBudget = thinkingBudget;
        }

        public boolean getIncludeThoughts() {
            return includeThoughts;
        }

        public void setIncludeThoughts(boolean includeThoughts) {
            this.includeThoughts = includeThoughts;
        }
    }
}
