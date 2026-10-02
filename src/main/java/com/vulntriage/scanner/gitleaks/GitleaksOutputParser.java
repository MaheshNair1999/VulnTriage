package com.vulntriage.scanner.gitleaks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private static final int CONTEXT_LINES = 10;
    private final ObjectMapper mapper = new ObjectMapper();
    private String repositoryPath = null;

    public void setRepositoryPath(String repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

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

        // Try to read context from file; fall back to the matched line
        String snippet = readSnippetFromFile(f.getFilePath(), startLine > 0 ? startLine : null);
        if (snippet != null) {
            f.setCodeSnippet(snippet);
        } else if (!match.isBlank()) {
            f.setCodeSnippet(match);
        }

        return f;
    }

    private String readSnippetFromFile(String filePath, Integer lineNumber) {
        if (filePath == null || lineNumber == null || lineNumber <= 0) return null;
        try {
            Path path;
            if (repositoryPath != null && !Paths.get(filePath).isAbsolute()) {
                path = Paths.get(repositoryPath, filePath);
            } else {
                path = Paths.get(filePath);
            }
            if (!Files.exists(path)) return null;

            List<String> lines = Files.readAllLines(path);
            int totalLines = lines.size();
            int targetLine = lineNumber - 1;
            if (targetLine < 0 || targetLine >= totalLines) return null;

            int start = Math.max(0, targetLine - CONTEXT_LINES);
            int end   = Math.min(totalLines - 1, targetLine + CONTEXT_LINES);

            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) {
                sb.append(lines.get(i)).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
