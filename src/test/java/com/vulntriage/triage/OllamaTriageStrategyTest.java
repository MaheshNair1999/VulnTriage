package com.vulntriage.triage;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.triage.api.TriageException;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.ollama.OllamaClient;
import com.vulntriage.triage.ollama.OllamaResponseParser;
import com.vulntriage.triage.ollama.OllamaTriageStrategy;
import com.vulntriage.triage.ollama.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests OllamaTriageStrategy using a stub OllamaClient.
 * No actual Ollama instance needed.
 */
class OllamaTriageStrategyTest {

    // ── Stub OllamaClient ──────────────────────────────────────────────────

    /** Returns a fixed JSON response on every call */
    private static OllamaClient fixedResponseClient(String json) {
        return new OllamaClient("http://localhost:11434", "test-model") {
            @Override public String generate(String prompt, int timeout) { return json; }
            @Override public boolean isReachable()       { return true; }
            @Override public boolean isModelAvailable()  { return true; }
        };
    }

    /** Throws TriageException on every call */
    private static OllamaClient alwaysFailClient() {
        return new OllamaClient("http://localhost:11434", "test-model") {
            @Override public String generate(String prompt, int timeout) {
                throw new TriageException("Connection refused");
            }
            @Override public boolean isReachable()      { return false; }
            @Override public boolean isModelAvailable() { return false; }
        };
    }

    /** Fails once, then succeeds */
    private static OllamaClient failOnceThenSucceedClient(String successJson) {
        return new OllamaClient("http://localhost:11434", "test-model") {
            private int calls = 0;
            @Override public String generate(String prompt, int timeout) {
                calls++;
                if (calls == 1) throw new TriageException("Malformed JSON on first attempt");
                return successJson;
            }
            @Override public boolean isReachable()      { return true; }
            @Override public boolean isModelAvailable() { return true; }
        };
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    private Finding testFinding;

    @BeforeEach
    void setup() {
        testFinding = new Finding();
        testFinding.setId        (1L);
        testFinding.setRuleId    ("python.django.xss.test");
        testFinding.setSeverity  (Severity.WARNING);
        testFinding.setCategory  ("xss");
        testFinding.setMessage   ("Potential XSS");
        testFinding.setCodeSnippet("{{ user_input|safe }}");
    }

    private OllamaTriageStrategy strategy(OllamaClient client) {
        return new OllamaTriageStrategy(client, new PromptBuilder(), new OllamaResponseParser());
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void triage_successfulTpResponse() {
        String json = """
            {
              "llm_verdict": "TP",
              "llm_confidence": 92,
              "llm_reasoning": "User input marked safe without sanitisation.",
              "llm_remediation": "Use django.utils.html.escape()."
            }
            """;
        TriageResult result = strategy(fixedResponseClient(json)).triage(testFinding);

        assertEquals(Verdict.TP, result.getVerdict());
        assertEquals(92,         result.getConfidence());
        assertFalse(result.getReasoning().isBlank());
        assertEquals(PromptBuilder.PROMPT_VERSION, result.getPromptVersion());
    }

    @Test
    void triage_successfulFpResponse() {
        String json = """
            {
              "llm_verdict": "FP",
              "llm_confidence": 85,
              "llm_reasoning": "Django auto-escaping is on.",
              "llm_remediation": "No action needed."
            }
            """;
        TriageResult result = strategy(fixedResponseClient(json)).triage(testFinding);
        assertEquals(Verdict.FP, result.getVerdict());
    }

    @Test
    void triage_retrySucceedsOnSecondAttempt() {
        // First call fails (bad JSON), second call succeeds
        String goodJson = """
            {
              "llm_verdict": "REVIEW",
              "llm_confidence": 55,
              "llm_reasoning": "Need more context.",
              "llm_remediation": "Manual review required."
            }
            """;
        OllamaClient client = failOnceThenSucceedClient(goodJson);
        TriageResult result = strategy(client).triage(testFinding);

        assertEquals(Verdict.REVIEW, result.getVerdict());
    }

    @Test
    void triage_allRetriesExhausted_throwsTriageException() {
        assertThrows(TriageException.class,
            () -> strategy(alwaysFailClient()).triage(testFinding));
    }

    @Test
    void isAvailable_reachableAndModelPresent_returnsTrue() {
        assertTrue(strategy(fixedResponseClient("{}")).isAvailable());
    }

    @Test
    void isAvailable_notReachable_returnsFalse() {
        assertFalse(strategy(alwaysFailClient()).isAvailable());
    }

    @Test
    void getModelName_returnsTestModel() {
        OllamaClient client = fixedResponseClient("{}");
        assertEquals("test-model", strategy(client).getModelName());
    }
}
