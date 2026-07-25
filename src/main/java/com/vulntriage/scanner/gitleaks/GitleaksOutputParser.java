package com.vulntriage.scanner.gitleaks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the JSON report written by `gitleaks detect --report-format json`.
 *
 * Gitleaks v8 report is a JSON array (or empty / null when nothing found):
 * [
 *   {
 *     "Description": "AWS Access Token",
 *     "StartLine": 12,
 *     "EndLine": 12,
 *     "File": "config/settings.py",
 *     "Secret": "AKIAIOSFODNN7EXAMPLE",
 *     "RuleID": "aws-access-token",
 *     "Match": "AWS_SECRET=AKIAIOSFODNN7EXAMPLE",
 *     "Entropy": 3.7
 *   }
 * ]
 */
public class GitleaksOutputParser {

    private static final Logger log = LoggerFactory.getLogger(GitleaksOutputParser.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public List<RawFinding> parse(String json) {
        List<RawFinding> findings = new ArrayList<>();
        String trimmed = json.trim();
        if (trimmed.isEmpty() || trimmed.equals("null") || trimmed.equals("[]")) {
            return findings;
        }

        try {
            JsonNode root = mapper.readTree(trimmed);
            if (!root.isArray()) {
                log.warn("Gitleaks output is not a JSON array — 0 findings returned");
                return findings;
            }

            for (JsonNode leak : root) {
                try {
                    findings.add(parseLeak(leak));
                } catch (Exception e) {
                    log.warn("Skipping malformed Gitleaks entry: {}", e.getMessage());
                }
            }

            log.info("Parsed {} secret findings from Gitleaks output", findings.size());

        } catch (Exception e) {
            throw new ScannerException("Failed to parse Gitleaks JSON output", e);
        }

        return findings;
    }

    private RawFinding parseLeak(JsonNode leak) {
        RawFinding f = new RawFinding();
        f.setSource("GITLEAKS");

        String ruleId = leak.path("RuleID").asText("unknown-secret");
        f.setRuleId(ruleId);

        f.setFilePath(leak.path("File").asText("unknown"));

        int startLine = leak.path("StartLine").asInt(0);
        f.setLineNumber(startLine > 0 ? startLine : null);

        // Secrets are always high severity
        f.setSeverity("ERROR");
        f.setCategory("secret");

        String description = leak.path("Description").asText("");
        String match       = leak.path("Match").asText("");
        String message = description.isBlank() ? "Secret detected: " + ruleId : description;
        f.setMessage(message);

        // Store the matched line as the code snippet (secret value is redacted by gitleaks itself)
        if (!match.isBlank()) {
            f.setCodeSnippet(match);
        }

        return f;
    }
}
