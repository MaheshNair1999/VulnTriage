package com.vulntriage.triage.api;

import com.vulntriage.domain.enums.Verdict;

/**
 * The output of a single LLM triage call on one finding.
 */
public class TriageResult {

    private Verdict verdict;
    private int     confidence;    // 0-100, self-reported by the model
    private String  reasoning;
    private String  remediation;
    private String  promptVersion; // track which prompt version was used

    public TriageResult() {}

    public TriageResult(Verdict verdict, int confidence,
                        String reasoning, String remediation,
                        String promptVersion) {
        this.verdict       = verdict;
        this.confidence    = confidence;
        this.reasoning     = reasoning;
        this.remediation   = remediation;
        this.promptVersion = promptVersion;
    }

    public Verdict getVerdict()          { return verdict; }
    public void    setVerdict(Verdict v) { this.verdict = v; }

    public int     getConfidence()       { return confidence; }
    public void    setConfidence(int c)  { this.confidence = c; }

    public String  getReasoning()        { return reasoning; }
    public void    setReasoning(String r){ this.reasoning = r; }

    public String  getRemediation()          { return remediation; }
    public void    setRemediation(String r)  { this.remediation = r; }

    public String  getPromptVersion()            { return promptVersion; }
    public void    setPromptVersion(String v)    { this.promptVersion = v; }
}
