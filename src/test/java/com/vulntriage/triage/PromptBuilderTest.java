package com.vulntriage.triage;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.triage.ollama.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    private PromptBuilder builder;

    @BeforeEach
    void setup() {
        builder = new PromptBuilder();
    }

    private Finding buildFinding() {
        Finding f = new Finding();
        f.setRuleId     ("python.django.security.audit.xss-safe-filter-escape");
        f.setSeverity   (Severity.WARNING);
        f.setCategory   ("xss");
        f.setMessage    ("Detected user input marked as safe without sanitisation.");
        f.setCodeSnippet("{{ user_input|safe }}");
        f.setSource     (ScannerType.SEMGREP);
        return f;
    }

    @Test
    void build_containsRuleId() {
        String prompt = builder.build(buildFinding());
        assertTrue(prompt.contains("python.django.security.audit.xss-safe-filter-escape"));
    }

    @Test
    void build_containsSeverity() {
        String prompt = builder.build(buildFinding());
        assertTrue(prompt.contains("WARNING"));
    }

    @Test
    void build_containsCategory() {
        String prompt = builder.build(buildFinding());
        assertTrue(prompt.contains("xss"));
    }

    @Test
    void build_containsMessage() {
        String prompt = builder.build(buildFinding());
        assertTrue(prompt.contains("sanitisation"));
    }

    @Test
    void build_containsCodeSnippet() {
        String prompt = builder.build(buildFinding());
        assertTrue(prompt.contains("user_input|safe"));
    }

    @Test
    void build_containsJsonInstruction() {
        String prompt = builder.build(buildFinding());
        assertTrue(prompt.contains("llm_verdict"));
        assertTrue(prompt.contains("llm_confidence"));
        assertTrue(prompt.contains("llm_reasoning"));
        assertTrue(prompt.contains("llm_remediation"));
    }

    @Test
    void build_containsJsonOnlyDirective() {
        String prompt = builder.build(buildFinding());
        // Must contain the key instruction to return only JSON
        assertTrue(prompt.contains("ONLY valid JSON"));
    }

    @Test
    void build_nullCodeSnippet_usesFallback() {
        Finding f = buildFinding();
        f.setCodeSnippet(null);
        String prompt = builder.build(f);
        assertTrue(prompt.contains("no code snippet available"));
    }

    @Test
    void build_nullFields_doesNotThrow() {
        Finding f = new Finding(); // all fields null
        assertDoesNotThrow(() -> builder.build(f));
    }

    @Test
    void promptVersion_isNotBlank() {
        assertFalse(PromptBuilder.PROMPT_VERSION.isBlank());
    }
}
