package com.vulntriage.scanner.trivy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the JSON output of `trivy fs --format json` into RawFinding objects.
 *
 * Trivy JSON structure:
 * {
 *   "Results": [
 *     {
 *       "Target": "requirements.txt",
 *       "Type": "pip",
 *       "Vulnerabilities": [
 *         {
 *           "VulnerabilityID": "CVE-2023-12345",
 *           "PkgName": "django",
 *           "InstalledVersion": "3.2.0",
 *           "FixedVersion": "3.2.15",
 *           "Severity": "MEDIUM",
 *           "Title": "...",
 *           "Description": "..."
 *         }
 *       ]
 *     }
 *   ]
 * }
 *
 * Note: Trivy findings have no line number (they reference packages, not code lines).
 * The filePath is set to the dependency file (requirements.txt, package-lock.json, etc.)
 */
public class TrivyOutputParser {

    private static final Logger log = LoggerFactory.getLogger(TrivyOutputParser.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public List<RawFinding> parse(String json) {
        List<RawFinding> findings = new ArrayList<>();

        try {
            JsonNode root    = mapper.readTree(json);
            JsonNode results = root.path("Results");

            if (!results.isArray()) {
                log.warn("Trivy output has no 'Results' array — returned 0 findings");
                return findings;
            }

            for (JsonNode result : results) {
                String target = result.path("Target").asText("unknown");
                JsonNode vulns = result.path("Vulnerabilities");

                if (!vulns.isArray() || vulns.isEmpty()) continue;

                for (JsonNode vuln : vulns) {
                    try {
                        findings.add(parseVulnerability(vuln, target));
                    } catch (Exception e) {
                        log.warn("Skipping malformed Trivy vulnerability: {}", e.getMessage());
                    }
                }
            }

            log.info("Parsed {} findings from Trivy output", findings.size());

        } catch (Exception e) {
            throw new ScannerException("Failed to parse Trivy JSON output", e);
        }

        return findings;
    }

    private RawFinding parseVulnerability(JsonNode vuln, String target) {
        RawFinding f = new RawFinding();
        f.setSource("TRIVY");

        String cveId  = vuln.path("VulnerabilityID").asText("unknown");
        String pkgName = vuln.path("PkgName").asText("unknown");

        // RuleId for Trivy = CVE-ID (e.g. CVE-2023-12345)
        f.setRuleId(cveId);
        f.setCveId (cveId);

        // FilePath = dependency manifest file (requirements.txt etc.)
        f.setFilePath(target);

        // No line number for dependency findings
        f.setLineNumber(null);

        // Severity: map Trivy levels to our schema
        String rawSeverity = vuln.path("Severity").asText("UNKNOWN");
        f.setSeverity(mapSeverity(rawSeverity));

        // Category is always "dependency" for SCA findings
        f.setCategory("dependency");

        // Message: combine title + package info
        String title       = vuln.path("Title").asText("");
        String description = vuln.path("Description").asText("");
        String msg = title.isBlank() ? description : title;
        if (msg.isBlank()) msg = "Vulnerability in " + pkgName;
        f.setMessage(msg);

        // Trivy-specific fields
        f.setPackageName      (pkgName);
        f.setInstalledVersion (vuln.path("InstalledVersion").asText(""));
        f.setFixedVersion     (vuln.path("FixedVersion").asText(""));

        // CWE — Trivy emits an array like ["CWE-79"] under "CweIDs"
        JsonNode cweIds = vuln.path("CweIDs");
        if (cweIds.isArray() && cweIds.size() > 0) {
            f.setCwe(cweIds.get(0).asText("").trim());
        }

        return f;
    }

    /**
     * Map Trivy severity strings to our Severity enum names.
     * Trivy uses: CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN
     * Our schema uses: INFO, WARNING, ERROR
     */
    public static String mapSeverity(String trivySeverity) {
        if (trivySeverity == null) return "INFO";
        return switch (trivySeverity.toUpperCase()) {
            case "CRITICAL", "HIGH" -> "ERROR";
            case "MEDIUM"           -> "WARNING";
            default                 -> "INFO";     // LOW, UNKNOWN
        };
    }
}
