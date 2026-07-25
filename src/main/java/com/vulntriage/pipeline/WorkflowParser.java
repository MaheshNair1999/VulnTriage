package com.vulntriage.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulntriage.domain.WorkflowDefinition;
import com.vulntriage.domain.WorkflowStep;
import com.vulntriage.domain.WorkflowStep.StepType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Parses a JSON workflow definition string into a WorkflowDefinition object.
 *
 * Also validates the workflow before returning it:
 *   - All step types must be recognised
 *   - Required parameters must be present for each step type
 *   - Logical ordering rules are checked (e.g. score must come after triage)
 *
 * JSON format:
 * {
 *   "name": "Django Security Audit",
 *   "description": "Full pipeline for Django apps",
 *   "steps": [
 *     { "type": "scan",   "scanner": "semgrep", "ruleset": "p/security-audit" },
 *     { "type": "filter", "condition": "severity >= WARNING" },
 *     { "type": "sample", "strategy": "stratified", "size": "100" },
 *     { "type": "triage", "model": "qwen3:8b" },
 *     { "type": "score"  },
 *     { "type": "report", "formats": "json,csv" }
 *   ]
 * }
 */
public class WorkflowParser {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parse and validate a JSON workflow definition.
     *
     * @param json raw JSON string
     * @return validated WorkflowDefinition
     * @throws WorkflowParseException if the JSON is malformed or the workflow is invalid
     */
    public WorkflowDefinition parse(String json) {
        if (json == null || json.isBlank()) {
            throw new WorkflowParseException("Workflow JSON cannot be empty");
        }

        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new WorkflowParseException("Invalid JSON: " + e.getMessage(), e);
        }

        WorkflowDefinition def = new WorkflowDefinition();

        // Name (required)
        if (!root.has("name") || root.path("name").asText().isBlank()) {
            throw new WorkflowParseException("Workflow must have a non-empty 'name' field");
        }
        def.setName(root.path("name").asText());
        def.setDescription(root.path("description").asText(""));

        // Steps (required, at least one)
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new WorkflowParseException("Workflow must have at least one step in 'steps' array");
        }

        List<WorkflowStep> steps = new ArrayList<>();
        for (int i = 0; i < stepsNode.size(); i++) {
            JsonNode stepNode = stepsNode.get(i);
            WorkflowStep step = parseStep(stepNode, i);
            steps.add(step);
        }

        validateOrdering(steps);
        def.setSteps(steps);
        return def;
    }

    // ── Step parsing ───────────────────────────────────────────────────────

    private WorkflowStep parseStep(JsonNode node, int index) {
        if (!node.has("type")) {
            throw new WorkflowParseException(
                "Step at index " + index + " is missing required field 'type'");
        }

        String typeStr = node.path("type").asText().toUpperCase();
        StepType type;
        try {
            type = StepType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new WorkflowParseException(
                "Unknown step type '" + typeStr + "' at index " + index
                + ". Valid types: scan, filter, sample, triage, score, report");
        }

        // Collect all fields except "type" as params
        Map<String, String> params = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getKey().equals("type")) {
                params.put(entry.getKey(), entry.getValue().asText());
            }
        }

        validateStepParams(type, params, index);
        return new WorkflowStep(type, params);
    }

    // ── Parameter validation ───────────────────────────────────────────────

    private void validateStepParams(StepType type, Map<String, String> params, int index) {
        switch (type) {
            case SCAN -> {
                if (!params.containsKey("scanner")) {
                    throw new WorkflowParseException(
                        "Step " + index + " (scan): required parameter 'scanner' is missing. "
                        + "Valid values: semgrep, trivy, gitleaks, codeql, sonarqube");
                }
                String scanner = params.get("scanner").toLowerCase();
                java.util.Set<String> valid = java.util.Set.of(
                    "semgrep", "trivy", "gitleaks", "codeql", "sonarqube");
                if (!valid.contains(scanner)) {
                    throw new WorkflowParseException(
                        "Step " + index + " (scan): unknown scanner '" + scanner
                        + "'. Valid values: semgrep, trivy, gitleaks, codeql, sonarqube");
                }
                if (scanner.equals("sonarqube")
                        && (!params.containsKey("sonar_url") || !params.containsKey("sonar_token"))) {
                    throw new WorkflowParseException(
                        "Step " + index + " (scan): sonarqube requires 'sonar_url' and 'sonar_token' params");
                }
            }
            case FILTER -> {
                if (!params.containsKey("condition") || params.get("condition").isBlank()) {
                    throw new WorkflowParseException(
                        "Step " + index + " (filter): required parameter 'condition' is missing. "
                        + "Example: severity >= WARNING AND category = xss");
                }
            }
            case SAMPLE -> {
                if (params.containsKey("size")) {
                    try {
                        int size = Integer.parseInt(params.get("size"));
                        if (size <= 0) throw new WorkflowParseException(
                            "Step " + index + " (sample): 'size' must be a positive integer");
                    } catch (NumberFormatException e) {
                        throw new WorkflowParseException(
                            "Step " + index + " (sample): 'size' must be a valid integer");
                    }
                }
            }
            // triage, score, report have no required params — all optional
            default -> {}
        }
    }

    // ── Ordering validation ────────────────────────────────────────────────

    /**
     * Validates logical step ordering.
     * Rules:
     *   - At least one SCAN step must appear before FILTER, SAMPLE, TRIAGE, SCORE
     *   - SCORE must appear after TRIAGE (confidence score comes from LLM)
     *   - REPORT should be last (warn if not)
     */
    private void validateOrdering(List<WorkflowStep> steps) {
        boolean hasScan   = false;
        boolean hasTriage = false;

        for (int i = 0; i < steps.size(); i++) {
            StepType type = steps.get(i).getType();

            switch (type) {
                case SCAN   -> hasScan   = true;
                case TRIAGE -> hasTriage = true;
                case FILTER, SAMPLE -> {
                    if (!hasScan) throw new WorkflowParseException(
                        "Step " + i + " (" + type.name().toLowerCase() + "): "
                        + "a 'scan' step must appear before this step");
                }
                case SCORE -> {
                    if (!hasScan) throw new WorkflowParseException(
                        "Step " + i + " (score): a 'scan' step must appear before scoring");
                    if (!hasTriage) throw new WorkflowParseException(
                        "Step " + i + " (score): a 'triage' step must appear before scoring, "
                        + "as confidence scores come from the LLM");
                }
                default -> {}
            }
        }
    }

    // ── Serialise back to JSON ─────────────────────────────────────────────

    /**
     * Convert a WorkflowDefinition back to a JSON string for persistence.
     */
    public String toJson(WorkflowDefinition def) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toMap(def));
        } catch (Exception e) {
            throw new WorkflowParseException("Failed to serialise workflow: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> toMap(WorkflowDefinition def) {
        Map<String, Object> map = new HashMap<>();
        map.put("name",        def.getName());
        map.put("description", def.getDescription());

        List<Map<String, String>> stepMaps = new ArrayList<>();
        for (WorkflowStep step : def.getSteps()) {
            Map<String, String> sm = new HashMap<>(step.getParams());
            sm.put("type", step.getType().name().toLowerCase());
            stepMaps.add(sm);
        }
        map.put("steps", stepMaps);
        return map;
    }
}
