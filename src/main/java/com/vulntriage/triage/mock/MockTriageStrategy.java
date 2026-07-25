package com.vulntriage.triage.mock;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.api.TriageStrategy;

/**
 * Deterministic mock triage strategy for testing.
 *
 * Rules (simple, predictable for assertions):
 *   - ERROR severity → TP, confidence 90
 *   - WARNING severity → FP, confidence 75
 *   - INFO severity → FP, confidence 60
 *
 * Never calls Ollama. Always available.
 * Used in pipeline integration tests and during UI development.
 */
public class MockTriageStrategy implements TriageStrategy {

    @Override
    public TriageResult triage(Finding finding) {
        Verdict verdict;
        int     confidence;
        String  reasoning;
        String  remediation;

        if (finding.getSeverity() == Severity.ERROR) {
            verdict     = Verdict.TP;
            confidence  = 90;
            reasoning   = "Mock: ERROR severity findings are treated as genuine vulnerabilities.";
            remediation = "Mock: Review and fix the flagged code.";
        } else if (finding.getSeverity() == Severity.WARNING) {
            verdict     = Verdict.FP;
            confidence  = 75;
            reasoning   = "Mock: WARNING severity findings are often false positives.";
            remediation = "No action required.";
        } else {
            verdict     = Verdict.FP;
            confidence  = 60;
            reasoning   = "Mock: INFO severity findings are typically informational.";
            remediation = "No action required.";
        }

        return new TriageResult(verdict, confidence, reasoning, remediation, "mock-v1.0");
    }

    @Override
    public String getModelName() { return "mock"; }

    @Override
    public boolean isAvailable() { return true; }
}
