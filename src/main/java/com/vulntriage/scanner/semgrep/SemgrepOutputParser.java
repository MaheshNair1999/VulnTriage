package com.vulntriage.scanner.semgrep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the JSON output of `semgrep scan --json` into RawFinding objects.
 *
 * Kept separate from SemgrepAdapter so it can be unit tested
 * without running the actual Semgrep binary.
 *
 * Semgrep JSON structure:
 * {
 *   "results": [
 *     {
 *       "check_id": "python.django.security...",
 *       "path": "app/views.py",
 *       "start": { "line": 45 },
 *       "extra": {
 *         "message": "...",
 *         "severity": "WARNING",
 *         "lines": "{{ user_input|safe }}",
 *         "metadata": { "category": "security" }
 *       }
 *     }
 *   ]
 * }
 */
public class SemgrepOutputParser {

    private static final int CONTEXT_LINES = 3; // lines before and after flagged line
    private String repositoryPath = null; // set before parsing to enable file reading

    public void setRepositoryPath(String path) { this.repositoryPath = path; }

    private static final Logger log = LoggerFactory.getLogger(SemgrepOutputParser.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parse Semgrep JSON output string into a list of RawFindings.
     *
     * @param json raw JSON string from semgrep stdout
     * @return list of parsed findings (may be empty)
     * @throws ScannerException if the JSON cannot be parsed
     */
    public List<RawFinding> parse(String json) {
        List<RawFinding> findings = new ArrayList<>();

        try {
            JsonNode root    = mapper.readTree(json);
            JsonNode results = root.path("results");

            if (!results.isArray()) {
                log.warn("Semgrep output has no 'results' array — returned 0 findings");
                return findings;
            }

            for (JsonNode result : results) {
                try {
                    findings.add(parseResult(result));
                } catch (Exception e) {
                    // Skip malformed individual results rather than failing the whole scan
                    log.warn("Skipping malformed Semgrep result: {}", e.getMessage());
                }
            }

            log.info("Parsed {} findings from Semgrep output", findings.size());

        } catch (Exception e) {
            throw new ScannerException("Failed to parse Semgrep JSON output", e);
        }

        return findings;
    }

    private RawFinding parseResult(JsonNode result) {
        RawFinding f = new RawFinding();
        f.setSource("SEMGREP");

        // Rule / check ID
        f.setRuleId(result.path("check_id").asText("unknown"));

        // File path
        f.setFilePath(result.path("path").asText("unknown"));

        // Line number
        JsonNode start = result.path("start");
        if (!start.isMissingNode()) {
            f.setLineNumber(start.path("line").asInt(0));
        }

        // Extra metadata
        JsonNode extra = result.path("extra");

        f.setMessage (extra.path("message").asText(""));
        f.setSeverity(extra.path("severity").asText("INFO"));

        // Code snippet — read directly from file using line number
        // This is more reliable than Semgrep's 'lines' field which sometimes
        // captures surrounding context rather than the actual flagged code
        String snippet = readSnippetFromFile(f.getFilePath(), f.getLineNumber());
        if (snippet != null) {
            f.setCodeSnippet(snippet);
        } else {
            // Fall back to Semgrep's lines field if file reading fails
            String semgrepLines = extra.path("lines").asText(null);
            if (semgrepLines != null && !semgrepLines.isBlank()) {
                f.setCodeSnippet(semgrepLines.trim());
            }
        }

        // Category — derive from rule ID if not in metadata
        JsonNode metadata = extra.path("metadata");
        String category = metadata.path("category").asText(null);
        if (category == null || category.isBlank()) {
            category = deriveCategoryFromRuleId(f.getRuleId());
        }
        f.setCategory(category.toLowerCase());

        // CWE — Semgrep emits an array like ["CWE-89: SQL Injection"]
        // We store only the identifier prefix (e.g. "CWE-89")
        JsonNode cweNode = metadata.path("cwe");
        if (cweNode.isArray() && cweNode.size() > 0) {
            String rawCwe = cweNode.get(0).asText("").trim();
            // Strip description after colon if present: "CWE-89: SQL Injection" -> "CWE-89"
            int colonIdx = rawCwe.indexOf(':');
            f.setCwe(colonIdx > 0 ? rawCwe.substring(0, colonIdx).trim() : rawCwe);
        } else if (cweNode.isTextual()) {
            String rawCwe = cweNode.asText("").trim();
            int colonIdx = rawCwe.indexOf(':');
            f.setCwe(colonIdx > 0 ? rawCwe.substring(0, colonIdx).trim() : rawCwe);
        }

        return f;
    }

    /**
     * Infer a vulnerability category from the Semgrep rule ID.
     * Rule IDs follow the pattern: lang.framework.security.audit.<category>-...
     */
    public static String deriveCategoryFromRuleId(String ruleId) {
        if (ruleId == null) return "unknown";
        String lower = ruleId.toLowerCase();
        if (lower.contains("xss"))                return "xss";
        if (lower.contains("sql"))                return "sql_injection";
        if (lower.contains("command") ||
            lower.contains("injection"))          return "command_injection";
        if (lower.contains("csrf"))               return "csrf";
        if (lower.contains("template"))           return "template_injection";
        if (lower.contains("xxe"))                return "xxe";
        if (lower.contains("path") ||
            lower.contains("traversal"))          return "path_traversal";
        if (lower.contains("secret") ||
            lower.contains("hardcoded"))          return "hardcoded_secret";
        if (lower.contains("ssrf"))               return "ssrf";
        if (lower.contains("deserializ"))         return "deserialization";
        return "security";
    }

    /**
     * Read the actual code around the flagged line directly from the source file.
     * Returns CONTEXT_LINES before and after the flagged line for context.
     * Returns null if the file cannot be read (network drive, deleted, etc.)
     */
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

            // Convert to 0-based index
            int targetLine = lineNumber - 1;
            if (targetLine < 0 || targetLine >= totalLines) return null;

            int start = Math.max(0, targetLine - CONTEXT_LINES);
            int end   = Math.min(totalLines - 1, targetLine + CONTEXT_LINES);

            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) {
                // Mark the flagged line with a pointer
                String prefix = (i == targetLine) ? ">>> " : "    ";
                sb.append(prefix).append(allLines.get(i)).append("\n");
            }
            return sb.toString().trim();

        } catch (IOException e) {
            // File unreadable (binary, wrong encoding, etc.) — fall back to Semgrep's output
            return null;
        }
    }
}
