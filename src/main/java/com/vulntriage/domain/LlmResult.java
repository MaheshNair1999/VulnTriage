package com.vulntriage.domain;

import com.vulntriage.domain.enums.Verdict;
import java.time.LocalDateTime;

/**
 * Records the output of one LLM triage call on a specific finding.
 *
 * Note on confidence: the value (0-100) is self-reported by the model and is
 * NOT a calibrated probability. It is stored for ranking purposes only.
 */
public class LlmResult {

    private long          id;
    private long          findingId;
    private long          evaluationRunId;
    private Verdict       llmVerdict;
    private int           confidence;     // 0-100, self-reported, not calibrated
    private String        reasoning;
    private String        remediation;
    private String        modelUsed;      // e.g. "qwen3:8b"
    private String        promptVersion;  // e.g. "v1.0" — track prompt changes
    private LocalDateTime createdAt;

    public LlmResult() {}

    // ── Getters & Setters ──────────────────────────────────────────────────

    public long getId()                              { return id; }
    public void setId(long id)                       { this.id = id; }

    public long getFindingId()                       { return findingId; }
    public void setFindingId(long findingId)         { this.findingId = findingId; }

    public long getEvaluationRunId()                 { return evaluationRunId; }
    public void setEvaluationRunId(long id)          { this.evaluationRunId = id; }

    public Verdict getLlmVerdict()                   { return llmVerdict; }
    public void setLlmVerdict(Verdict v)             { this.llmVerdict = v; }

    public int getConfidence()                       { return confidence; }
    public void setConfidence(int confidence)        { this.confidence = confidence; }

    public String getReasoning()                     { return reasoning; }
    public void setReasoning(String reasoning)       { this.reasoning = reasoning; }

    public String getRemediation()                   { return remediation; }
    public void setRemediation(String remediation)   { this.remediation = remediation; }

    public String getModelUsed()                     { return modelUsed; }
    public void setModelUsed(String modelUsed)       { this.modelUsed = modelUsed; }

    public String getPromptVersion()                 { return promptVersion; }
    public void setPromptVersion(String v)           { this.promptVersion = v; }

    public LocalDateTime getCreatedAt()                    { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)      { this.createdAt = createdAt; }
}
