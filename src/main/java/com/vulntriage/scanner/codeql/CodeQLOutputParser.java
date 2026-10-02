package com.vulntriage.scanner.codeql;

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
 * Parses SARIF 2.1 output produced by `codeql database analyze --format=sarif-latest`.
 *
 * SARIF shape relevant to us:
 * {
 *   "runs": [{
 *     "tool": { "driver": { "rules": [ { "id": "java/sql-injection",
 *       "properties": { "tags": ["cwe/cwe-089"] } } ] } },
 *     "results": [{
 *       "ruleId": "java/sql-injection",
 *       "ruleIndex": 0,
 *       "level": "error",
 *       "message": { "text": "This query depends on user-controlled input." },
 *       "locations": [{
 *         "physicalLocation": {
 *           "artifactLocation": { "uri": "src/main/App.java", "uriBaseId": "%SRCROOT%" },
 *           "region": { "startLine": 45 }
 *         }
 *       }]
 *     }]
 *   }]
 * }
 */
public class CodeQLOutputParser {

    private static final Logger log = LoggerFactory.getLogger(CodeQLOutputParser.class);
    private static final int CONTEXT_LINES = 3;
    private final ObjectMapper mapper = new ObjectMapper();
    private String repositoryPath = null;

    public void setRepositoryPath(String repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    public List<RawFinding> parse(String sarifJson) {
        List<RawFinding> findings = new ArrayList<>();

        try {
            JsonNode root = mapper.readTree(sarifJson);
            JsonNode runs = root.path("runs");
            if (!runs.isArray()) {
                log.warn("SARIF output has no 'runs' array");
                return findings;
            }

            for (JsonNode run : runs) {
                // Build rule metadata index for CWE extraction
                JsonNode rules = run.path("tool").path("driver").path("rules");
                String[] ruleIds  = extractRuleIds(rules);
                String[] ruleCwes = extractRuleCwes(rules);

                JsonNode results = run.path("results");
                if (!results.isArray()) continue;

                for (JsonNode result : results) {
                    try {
                        findings.add(parseResult(result, ruleIds, ruleCwes));
                    } catch (Exception e) {
                        log.warn("Skipping malformed SARIF result: {}", e.getMessage());
                    }
                }
            }

            log.info("Parsed {} findings from CodeQL SARIF output", findings.size());

        } catch (Exception e) {
            throw new ScannerException("Failed to parse CodeQL SARIF output", e);
        }

        return findings;
    }

    private RawFinding parseResult(JsonNode result, String[] ruleIds, String[] ruleCwes) {
        RawFinding f = new RawFinding();
        f.setSource("CODEQL");

        String ruleId = result.path("ruleId").asText("unknown");
        f.setRuleId(ruleId);

        // Severity from SARIF level: error → ERROR, warning → WARNING, note/none → INFO
        String level = result.path("level").asText("warning");
        f.setSeverity(mapLevel(level));

        f.setMessage(result.path("message").path("text").asText("No message"));
        f.setCategory("code-vulnerability");

        // Location
        JsonNode locations = result.path("locations");
        if (locations.isArray() && locations.size() > 0) {
            JsonNode physLoc = locations.get(0).path("physicalLocation");
            String uri = physLoc.path("artifactLocation").path("uri").asText("");
            f.setFilePath(uri);
            int startLine = physLoc.path("region").path("startLine").asInt(0);
            if (startLine > 0) f.setLineNumber(startLine);
        }

        // Code snippet
        String snippet = readSnippetFromFile(f.getFilePath(), f.getLineNumber());
        if (snippet != null) f.setCodeSnippet(snippet);

        // CWE: look up by ruleIndex or ruleId in the rules table
        int ruleIndex = result.path("ruleIndex").asInt(-1);
        if (ruleIndex >= 0 && ruleIndex < ruleCwes.length && ruleCwes[ruleIndex] != null) {
            f.setCwe(ruleCwes[ruleIndex]);
        }

        return f;
    }

    private String[] extractRuleIds(JsonNode rules) {
        if (!rules.isArray()) return new String[0];
        String[] ids = new String[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            ids[i] = rules.get(i).path("id").asText("");
        }
        return ids;
    }

    private String[] extractRuleCwes(JsonNode rules) {
        if (!rules.isArray()) return new String[0];
        String[] cwes = new String[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            JsonNode tags = rules.get(i).path("properties").path("tags");
            if (tags.isArray()) {
                for (JsonNode tag : tags) {
                    String t = tag.asText("");
                    // Tags like "external/cwe/cwe-089" or "cwe/cwe-089"
                    if (t.contains("cwe-") || t.contains("cwe/cwe")) {
                        String num = t.substring(t.lastIndexOf("cwe-") + 4).toUpperCase();
                        cwes[i] = "CWE-" + num;
                        break;
                    }
                }
            }
        }
        return cwes;
    }

    private static String mapLevel(String level) {
        return switch (level.toLowerCase()) {
            case "error"   -> "ERROR";
            case "warning" -> "WARNING";
            default        -> "INFO";
        };
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

            java.util.List<String> allLines = Files.readAllLines(path);
            int totalLines = allLines.size();
            int targetLine = lineNumber - 1;
            if (targetLine < 0 || targetLine >= totalLines) return null;

            int start = Math.max(0, targetLine - CONTEXT_LINES);
            int end   = Math.min(totalLines - 1, targetLine + CONTEXT_LINES);

            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) {
                String prefix = (i == targetLine) ? ">>> " : "    ";
                sb.append(prefix).append(allLines.get(i)).append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return null;
        }
    }
}
