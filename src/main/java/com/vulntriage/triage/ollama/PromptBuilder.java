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

    public static final String PROMPT_VERSION    = "v1.0";
    public static final String PROMPT_VERSION_V2 = "v2.0";

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

    // Context-enriched prompt (v2.0). /no_think disables Qwen3 extended thinking mode
    // so it returns a direct JSON response rather than verbose markdown prose.
    private static final String TEMPLATE_V2 = """
        /no_think
        You are a senior application security engineer. Classify this static analysis finding.

        IMPORTANT: Output ONLY the JSON object below. No markdown, no prose, no explanation outside the JSON.

        === FINDING ===
        Rule ID  : %s
        Severity : %s
        Category : %s
        Message  : %s
        Flagged line (%d):
        %s

        === SOURCE CONTEXT (%s) ===
        %s

        Use the source context to check:
        - Is Django auto-escaping active? (no |safe filter, no autoescape off)
        - Is the variable user-controlled or from a trusted/framework source?
        - Is there login_required or permission checks restricting access?
        - Is this test code (setUp, test helpers) rather than production code?

        Verdict options: TP (genuinely exploitable), FP (safe in context), REVIEW (still uncertain).

        Return ONLY this JSON — start with { and end with }, nothing else:
        {
          "llm_verdict": "TP" or "FP" or "REVIEW",
          "llm_confidence": <integer 0-100>,
          "llm_reasoning": "<1-2 sentences referencing specific context>",
          "llm_remediation": "<fix if TP, or why safe if FP>"
        }
        """;

    /** Build the snippet-only prompt (v1.0). */
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

    /**
     * Build the context-enriched prompt (v2.0).
     *
     * @param finding         the finding to triage
     * @param fullFileContent the complete source file containing the flagged line
     * @return complete prompt string ready to send to the LLM
     */
    public String buildV2(Finding finding, String fullFileContent) {
        String ruleId    = nullSafe(finding.getRuleId());
        String severity  = finding.getSeverity() != null
            ? finding.getSeverity().name() : "UNKNOWN";
        String category  = nullSafe(finding.getCategory());
        String message   = nullSafe(finding.getMessage());
        String snippet   = finding.getCodeSnippet() != null
            ? finding.getCodeSnippet().trim() : "(no code snippet available)";
        int    lineNo    = finding.getLineNumber() != null ? finding.getLineNumber() : 0;
        String filePath  = nullSafe(finding.getFilePath());
        String fileCtx   = fullFileContent != null ? fullFileContent : "(file not available)";

        return String.format(TEMPLATE_V2, ruleId, severity, category, message,
                             lineNo, snippet, filePath, fileCtx);
    }

    /**
     * Build a prompt from a stored template using named double-brace placeholders.
     *
     * Supported placeholders:
     *   {{rule_id}}, {{severity}}, {{category}}, {{message}},
     *   {{code_snippet}}, {{line_number}}, {{file_path}}, {{source_context}}
     *
     * @param finding       the finding to triage
     * @param template      the raw template string from the DB
     * @param sourceContext optional wider file context (80+80 lines); pass null if not available
     * @return              the fully expanded prompt ready to send to the LLM
     */
    public String buildFromTemplate(Finding finding, String template, String sourceContext) {
        String ruleId   = nullSafe(finding.getRuleId());
        String severity = finding.getSeverity() != null ? finding.getSeverity().name() : "UNKNOWN";
        String category = nullSafe(finding.getCategory());
        String message  = nullSafe(finding.getMessage());
        String snippet  = finding.getCodeSnippet() != null
            ? finding.getCodeSnippet().trim() : "(no code snippet available)";
        String lineNo   = finding.getLineNumber() != null
            ? String.valueOf(finding.getLineNumber()) : "0";
        String filePath = nullSafe(finding.getFilePath());
        String ctx      = sourceContext != null ? sourceContext : "(source context not available)";

        return template
            .replace("{{rule_id}}",       ruleId)
            .replace("{{severity}}",      severity)
            .replace("{{category}}",      category)
            .replace("{{message}}",       message)
            .replace("{{code_snippet}}",  snippet)
            .replace("{{line_number}}",   lineNo)
            .replace("{{file_path}}",     filePath)
            .replace("{{source_context}}", ctx);
    }

    private String nullSafe(String value) {
        return value != null ? value : "(unknown)";
    }
}
