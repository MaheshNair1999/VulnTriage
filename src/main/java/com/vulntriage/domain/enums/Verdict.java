package com.vulntriage.domain.enums;

/**
 * The three possible triage verdicts for a finding.
 * Used by both manual reviewers and the LLM triage engine.
 */
public enum Verdict {
    TP,      // True Positive  — genuine exploitable vulnerability
    FP,      // False Positive — safe pattern flagged incorrectly
    REVIEW   // Uncertain      — needs deeper inspection
}
