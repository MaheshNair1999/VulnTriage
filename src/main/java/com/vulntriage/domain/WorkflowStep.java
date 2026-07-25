package com.vulntriage.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * One step in a user-defined security workflow.
 *
 * A step has a type (scan, filter, sample, triage, score, report)
 * and a map of parameters whose keys depend on the step type.
 *
 * Examples:
 *   type=scan,   params={scanner=semgrep, ruleset=p/security-audit}
 *   type=filter, params={condition=severity >= WARNING AND category = xss}
 *   type=sample, params={strategy=stratified, size=100}
 *   type=triage, params={model=qwen3:8b}
 *   type=score,  params={formula=severity * category_weight * confidence}
 *   type=report, params={formats=json,csv}
 */
public class WorkflowStep {

    /** All supported step types. */
    public enum StepType {
        SCAN,    // invoke a scanner (Semgrep or Trivy)
        FILTER,  // apply a boolean filter expression
        SAMPLE,  // draw a stratified sample
        TRIAGE,  // run LLM triage
        SCORE,   // compute risk scores
        REPORT   // generate output (JSON, CSV)
    }

    private StepType            type;
    private Map<String, String> params = new HashMap<>();

    public WorkflowStep() {}

    public WorkflowStep(StepType type, Map<String, String> params) {
        this.type   = type;
        this.params = params;
    }

    public StepType getType()                       { return type; }
    public void     setType(StepType type)          { this.type = type; }

    public Map<String, String> getParams()                          { return params; }
    public void                setParams(Map<String, String> params) { this.params = params; }

    /** Convenience: get one parameter, returning defaultValue if absent. */
    public String param(String key, String defaultValue) {
        return params.getOrDefault(key, defaultValue);
    }

    @Override
    public String toString() {
        return "WorkflowStep{type=" + type + ", params=" + params + "}";
    }
}
