package com.vulntriage.triage.ollama;

import com.vulntriage.domain.Finding;

/**
 * Builds the structured prompt sent to the LLM for each finding.
 *
 * The prompt is versioned (PROMPT_VERSION constant) so that when the
 * prompt changes, old and new LLM results stored in the database can
 * be compared. LlmResult.promptVersion records which version was used.
 *
 * Design principles:
 *   1. Role framing   — "You are a senior security engineer" primes
 *                        the model to reason about security, not poetry.
 *   2. Structured task — numbered steps prevent the model from drifting.
 *   3. JSON-only output — critical: without this, Qwen3 adds prose before
 *                         the JSON that breaks the parser.
 *   4. No examples    — few-shot examples improve accuracy but consume
 *                        tokens; kept out to stay within 4K context.
 */
public class PromptBuilder {

    public static final String PROMPT_VERSION = "v1.0";

    private static final String TEMPLATE = """
        You are a senior application security engineer reviewing a static analysis finding.

        Your task is to determine whether this finding is a genuine vulnerability.

        Analyze the following Semgrep finding carefully:

        Rule ID  : %s
        Severity : %s
        Category : %s
        Message  : %s
        Code     : %s

        Instructions:
        1. Decide if this is a True Positive (TP), False Positive (FP), or needs Review (REVIEW).
           - TP: the code is genuinely vulnerable and exploitable.
           - FP: the code is safe — the rule fired incorrectly (e.g. framework handles escaping).
           - REVIEW: insufficient context to decide with confidence.
        2. Assign a confidence score from 0 to 100.
           Note: this is your subjective certainty, not a calibrated probability.
        3. Briefly explain your reasoning (1-3 sentences).
        4. If TP, suggest a concrete remediation. If FP, explain why it is safe.

        Return ONLY valid JSON — no preamble, no markdown, no explanation outside the JSON:
        {
          "llm_verdict": "TP" or "FP" or "REVIEW",
          "llm_confidence": <integer 0-100>,
          "llm_reasoning": "<your reasoning>",
          "llm_remediation": "<fix suggestion or explanation>"
        }
        """;

    /**
     * Build the full prompt for the given finding.
     *
     * @param finding the finding to triage
     * @return complete prompt string ready to send to the LLM
     */
    public String build(Finding finding) {
        String ruleId   = nullSafe(finding.getRuleId());
        String severity = finding.getSeverity() != null
            ? finding.getSeverity().name() : "UNKNOWN";
        String category = nullSafe(finding.getCategory());
        String message  = nullSafe(finding.getMessage());
        String code     = finding.getCodeSnippet() != null
            ? finding.getCodeSnippet().trim() : "(no code snippet available)";

        return String.format(TEMPLATE, ruleId, severity, category, message, code);
    }

    private String nullSafe(String value) {
        return value != null ? value : "(unknown)";
    }
}
