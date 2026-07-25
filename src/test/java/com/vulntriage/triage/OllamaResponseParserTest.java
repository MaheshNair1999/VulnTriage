package com.vulntriage.triage;

import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.triage.api.TriageException;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.ollama.OllamaResponseParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OllamaResponseParserTest {

    private OllamaResponseParser parser;

    @BeforeEach
    void setup() {
        parser = new OllamaResponseParser();
    }

    // ── Happy path — clean JSON ────────────────────────────────────────────

    @Test
    void parse_cleanJson_tp() {
        String raw = """
            {
              "llm_verdict": "TP",
              "llm_confidence": 95,
              "llm_reasoning": "User input passed to HttpResponse without escaping.",
              "llm_remediation": "Use django.utils.html.escape() before rendering."
            }
            """;
        TriageResult result = parser.parse(raw, "v1.0");

        assertEquals(Verdict.TP, result.getVerdict());
        assertEquals(95,         result.getConfidence());
        assertFalse(result.getReasoning().isBlank());
        assertFalse(result.getRemediation().isBlank());
        assertEquals("v1.0", result.getPromptVersion());
    }

    @Test
    void parse_cleanJson_fp() {
        String raw = """
            {
              "llm_verdict": "FP",
              "llm_confidence": 88,
              "llm_reasoning": "Django auto-escaping is enabled by default.",
              "llm_remediation": "No action required."
            }
            """;
        TriageResult result = parser.parse(raw, "v1.0");
        assertEquals(Verdict.FP, result.getVerdict());
        assertEquals(88,         result.getConfidence());
    }

    @Test
    void parse_cleanJson_review() {
        String raw = """
            {
              "llm_verdict": "REVIEW",
              "llm_confidence": 50,
              "llm_reasoning": "Cannot determine exploitability without more context.",
              "llm_remediation": "Manual review required."
            }
            """;
        TriageResult result = parser.parse(raw, "v1.0");
        assertEquals(Verdict.REVIEW, result.getVerdict());
    }

    // ── Resilience — LLM adds prose or markdown ────────────────────────────

    @Test
    void parse_jsonWrappedInMarkdown() {
        String raw = """
            Sure, here is my analysis:
            ```json
            {
              "llm_verdict": "FP",
              "llm_confidence": 80,
              "llm_reasoning": "Framework handles this safely.",
              "llm_remediation": "No action needed."
            }
            ```
            """;
        TriageResult result = parser.parse(raw, "v1.0");
        assertEquals(Verdict.FP, result.getVerdict());
        assertEquals(80,         result.getConfidence());
    }

    @Test
    void parse_jsonPrecededByProse() {
        String raw = """
            Based on my analysis of this Semgrep finding, here is my assessment:
            {
              "llm_verdict": "TP",
              "llm_confidence": 72,
              "llm_reasoning": "SQL injection via string concatenation.",
              "llm_remediation": "Use parameterised queries."
            }
            """;
        TriageResult result = parser.parse(raw, "v1.0");
        assertEquals(Verdict.TP, result.getVerdict());
        assertEquals(72,         result.getConfidence());
    }

    @Test
    void parse_confidenceClamped_above100() {
        String raw = """
            {
              "llm_verdict": "FP",
              "llm_confidence": 150,
              "llm_reasoning": "Safe.",
              "llm_remediation": "Nothing."
            }
            """;
        TriageResult result = parser.parse(raw, "v1.0");
        assertEquals(100, result.getConfidence());
    }

    @Test
    void parse_confidenceClamped_below0() {
        String raw = """
            {
              "llm_verdict": "TP",
              "llm_confidence": -5,
              "llm_reasoning": "Vulnerable.",
              "llm_remediation": "Fix it."
            }
            """;
        TriageResult result = parser.parse(raw, "v1.0");
        assertEquals(0, result.getConfidence());
    }

    @Test
    void parse_unknownVerdict_defaultsToReview() {
        String raw = """
            {
              "llm_verdict": "MAYBE",
              "llm_confidence": 60,
              "llm_reasoning": "Uncertain.",
              "llm_remediation": "Check manually."
            }
            """;
        TriageResult result = parser.parse(raw, "v1.0");
        assertEquals(Verdict.REVIEW, result.getVerdict());
    }

    // ── Failure cases ──────────────────────────────────────────────────────

    @Test
    void parse_emptyResponse_throwsTriageException() {
        assertThrows(TriageException.class, () -> parser.parse("", "v1.0"));
    }

    @Test
    void parse_blankResponse_throwsTriageException() {
        assertThrows(TriageException.class, () -> parser.parse("   ", "v1.0"));
    }

    @Test
    void parse_noJsonObject_throwsTriageException() {
        assertThrows(TriageException.class, () ->
            parser.parse("I cannot determine the vulnerability status.", "v1.0"));
    }

    // ── extractJson directly ───────────────────────────────────────────────

    @Test
    void extractJson_cleanJson_returnsSame() {
        String json = "{\"llm_verdict\":\"TP\",\"llm_confidence\":90,"
            + "\"llm_reasoning\":\"r\",\"llm_remediation\":\"f\"}";
        assertEquals(json.trim(), parser.extractJson(json).trim());
    }

    @Test
    void extractJson_withMarkdown_stripsBackticks() {
        String raw = "```json\n{\"llm_verdict\":\"FP\",\"llm_confidence\":80,"
            + "\"llm_reasoning\":\"safe\",\"llm_remediation\":\"none\"}\n```";
        String extracted = parser.extractJson(raw);
        assertTrue(extracted.startsWith("{"));
        assertTrue(extracted.contains("llm_verdict"));
    }
}
