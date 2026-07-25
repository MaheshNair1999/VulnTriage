package com.vulntriage.pipeline;

import com.vulntriage.domain.WorkflowDefinition;
import com.vulntriage.domain.WorkflowStep.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowParserTest {

    private WorkflowParser parser;

    @BeforeEach
    void setup() { parser = new WorkflowParser(); }

    private static final String VALID_WORKFLOW = """
        {
          "name": "Django Audit",
          "description": "Full pipeline",
          "steps": [
            { "type": "scan",   "scanner": "semgrep", "ruleset": "p/security-audit" },
            { "type": "filter", "condition": "severity >= WARNING" },
            { "type": "sample", "strategy": "stratified", "size": "100" },
            { "type": "triage", "model": "qwen3:8b" },
            { "type": "score"  },
            { "type": "report", "formats": "json,csv" }
          ]
        }
        """;

    // ── Happy path ─────────────────────────────────────────────────────────

    @Test
    void parse_validWorkflow_returnsCorrectName() {
        WorkflowDefinition def = parser.parse(VALID_WORKFLOW);
        assertEquals("Django Audit", def.getName());
    }

    @Test
    void parse_validWorkflow_returnsCorrectStepCount() {
        WorkflowDefinition def = parser.parse(VALID_WORKFLOW);
        assertEquals(6, def.getSteps().size());
    }

    @Test
    void parse_validWorkflow_firstStepIsScan() {
        WorkflowDefinition def = parser.parse(VALID_WORKFLOW);
        assertEquals(StepType.SCAN, def.getSteps().get(0).getType());
    }

    @Test
    void parse_validWorkflow_scanHasRulesetParam() {
        WorkflowDefinition def = parser.parse(VALID_WORKFLOW);
        assertEquals("p/security-audit",
            def.getSteps().get(0).param("ruleset", ""));
    }

    @Test
    void parse_validWorkflow_filterHasCondition() {
        WorkflowDefinition def = parser.parse(VALID_WORKFLOW);
        assertEquals("severity >= WARNING",
            def.getSteps().get(1).param("condition", ""));
    }

    @Test
    void parse_validWorkflow_sampleHasSize() {
        WorkflowDefinition def = parser.parse(VALID_WORKFLOW);
        assertEquals("100", def.getSteps().get(2).param("size", ""));
    }

    // ── Serialise round-trip ───────────────────────────────────────────────

    @Test
    void toJson_thenParse_preservesName() {
        WorkflowDefinition original = parser.parse(VALID_WORKFLOW);
        String json   = parser.toJson(original);
        WorkflowDefinition reparsed = parser.parse(json);
        assertEquals(original.getName(), reparsed.getName());
    }

    @Test
    void toJson_thenParse_preservesStepCount() {
        WorkflowDefinition original = parser.parse(VALID_WORKFLOW);
        String json   = parser.toJson(original);
        WorkflowDefinition reparsed = parser.parse(json);
        assertEquals(original.getSteps().size(), reparsed.getSteps().size());
    }

    // ── Validation errors ──────────────────────────────────────────────────

    @Test
    void parse_emptyJson_throwsException() {
        assertThrows(WorkflowParseException.class, () -> parser.parse(""));
    }

    @Test
    void parse_missingName_throwsException() {
        String json = """
            { "steps": [{ "type": "scan", "scanner": "semgrep" }] }
            """;
        assertThrows(WorkflowParseException.class, () -> parser.parse(json));
    }

    @Test
    void parse_emptySteps_throwsException() {
        String json = """
            { "name": "test", "steps": [] }
            """;
        assertThrows(WorkflowParseException.class, () -> parser.parse(json));
    }

    @Test
    void parse_unknownStepType_throwsException() {
        String json = """
            { "name": "test", "steps": [{ "type": "invalid" }] }
            """;
        assertThrows(WorkflowParseException.class, () -> parser.parse(json));
    }

    @Test
    void parse_scanMissingScanner_throwsException() {
        String json = """
            { "name": "test", "steps": [{ "type": "scan" }] }
            """;
        assertThrows(WorkflowParseException.class, () -> parser.parse(json));
    }

    @Test
    void parse_filterMissingCondition_throwsException() {
        String json = """
            { "name": "test", "steps": [
              { "type": "scan", "scanner": "semgrep" },
              { "type": "filter" }
            ]}
            """;
        assertThrows(WorkflowParseException.class, () -> parser.parse(json));
    }

    @Test
    void parse_filterBeforeScan_throwsException() {
        String json = """
            { "name": "test", "steps": [
              { "type": "filter", "condition": "severity >= WARNING" },
              { "type": "scan",   "scanner": "semgrep" }
            ]}
            """;
        assertThrows(WorkflowParseException.class, () -> parser.parse(json));
    }

    @Test
    void parse_scoreBeforeTriage_throwsException() {
        String json = """
            { "name": "test", "steps": [
              { "type": "scan",  "scanner": "semgrep" },
              { "type": "score" }
            ]}
            """;
        assertThrows(WorkflowParseException.class, () -> parser.parse(json));
    }

    @Test
    void parse_invalidJson_throwsException() {
        assertThrows(WorkflowParseException.class, () -> parser.parse("{ bad json "));
    }
}
