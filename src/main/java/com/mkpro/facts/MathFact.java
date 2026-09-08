package com.mkpro.facts;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A math/physics/CS fact with formula, keywords, and a Groovy verification script.
 */
public class MathFact {
    private String key;        // e.g. "geometry.circle_area"
    private String formula;    // e.g. "A = π × r²"
    private String script;     // Groovy script with verify() and validate() functions
    private List<String> keywords = Collections.emptyList();
    private Map<String, String> units = Collections.emptyMap();

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getFormula() { return formula; }
    public void setFormula(String formula) { this.formula = formula; }

    public String getScript() { return script; }
    public void setScript(String script) { this.script = script; }

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }

    public Map<String, String> getUnits() { return units; }
    public void setUnits(Map<String, String> units) { this.units = units; }

    @Override
    public String toString() {
        return key + ": " + formula;
    }
}
