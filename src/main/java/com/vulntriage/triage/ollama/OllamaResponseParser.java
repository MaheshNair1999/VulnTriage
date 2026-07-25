package com.vulntriage.triage.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.triage.api.TriageException;
import com.vulntriage.triage.api.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the raw LLM text response into a TriageResult.
 *
 * Even with "return ONLY valid JSON" in the prompt, Qwen3 occasionally
 * wraps the JSON in markdown (```json ... ```) or adds a sentence before it.
 * This parser handles those cases gracefully.
 *
 * Kept separate from OllamaTriageStrategy so it can be unit tested
 * without making any HTTP calls.
 */
public class OllamaResponseParser {

    private static final Logger log = LoggerFactory.getLogger(OllamaResponseParser.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    // Extracts a JSON object from anywhere in the response text
    private static final Pattern JSON_PATTERN =
        Pattern.compile("\\{[^{}]*\"llm_verdict\"[^{}]*\\}", Pattern.DOTALL);

    /**
     * Parse raw LLM response text into a TriageResult.
     *
     * @param rawResponse the full text returned by Ollama
     * @param promptVersion the prompt version used, stored for comparison
     * @return parsed TriageResult
     * @throws TriageException if no valid JSON object can be found or verdict is invalid
     */
    public TriageResult parse(String rawResponse, String promptVersion) {
        String json = extractJson(rawResponse);

        try {
            JsonNode node = mapper.readTree(json);

            String  verdictStr = node.path("llm_verdict").asText("").trim().toUpperCase();
            int     confidence = node.path("llm_confidence").asInt(50);
            String  reasoning  = node.path("llm_reasoning").asText("").trim();
            String  remediation = node.path("llm_remediation").asText("").trim();

            Verdict verdict = parseVerdict(verdictStr);

            // Clamp confidence to 0-100
            confidence = Math.max(0, Math.min(100, confidence));

            return new TriageResult(verdict, confidence, reasoning, remediation, promptVersion);

        } catch (TriageException e) {
            throw e;
        } catch (Exception e) {
            throw new TriageException(
                "Failed to parse LLM JSON response: " + e.getMessage()
                + "\nRaw response: " + rawResponse, e);
        }
    }

    /**
     * Extract the JSON object from the raw response.
     * Handles: plain JSON, markdown code blocks, JSON preceded by prose.
     */
    public String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new TriageException("LLM returned empty response");
        }

        // Try 1: response is clean JSON already
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        // Try 2: strip markdown code fences ```json ... ```
        if (trimmed.contains("```")) {
            String stripped = trimmed
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*",     "")
                .trim();
            if (stripped.startsWith("{")) return stripped;
        }

        // Try 3: find first JSON object containing llm_verdict
        Matcher m = JSON_PATTERN.matcher(raw);
        if (m.find()) {
            log.debug("Extracted JSON from mixed response using pattern matcher");
            return m.group();
        }

        throw new TriageException(
            "Could not find a valid JSON object in LLM response.\nRaw: " + raw);
    }

    private Verdict parseVerdict(String raw) {
        return switch (raw) {
            case "TP"     -> Verdict.TP;
            case "FP"     -> Verdict.FP;
            case "REVIEW" -> Verdict.REVIEW;
            default       -> {
                log.warn("Unexpected verdict '{}' — defaulting to REVIEW", raw);
                yield Verdict.REVIEW;
            }
        };
    }
}
