package com.vulntriage.scanner.trivy;

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
    private static final int CONTEXT_LINES = 3;
    private final ObjectMapper mapper = new ObjectMapper();
    private String repositoryPath = null;

    public void setRepositoryPath(String repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

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

        // CVSS v3 vector — prefer NVD source, fall back to redhat/ghsa
        JsonNode cvssNode = vuln.path("CVSS");
        if (cvssNode.isObject()) {
            for (String src : new String[]{"nvd", "redhat", "ghsa"}) {
                String v3 = cvssNode.path(src).path("V3Vector").asText(null);
                if (v3 != null && v3.startsWith("CVSS:3.")) {
                    f.setCvssVector(v3);
                    break;
                }
            }
        }

        // Snippet: find the package name in the manifest file
        String snippet = findPackageSnippet(target, pkgName);
        if (snippet != null) f.setCodeSnippet(snippet);

        return f;
    }

    private String findPackageSnippet(String target, String pkgName) {
        if (repositoryPath == null || target == null || pkgName == null) return null;
        try {
            Path path = Paths.get(repositoryPath, target);
            if (!Files.exists(path)) return null;

            List<String> lines = Files.readAllLines(path);
            int totalLines = lines.size();
            String needle = pkgName.toLowerCase();

            for (int i = 0; i < totalLines; i++) {
                if (lines.get(i).toLowerCase().contains(needle)) {
                    int start = Math.max(0, i - CONTEXT_LINES);
                    int end   = Math.min(totalLines - 1, i + CONTEXT_LINES);
                    StringBuilder sb = new StringBuilder();
                    for (int j = start; j <= end; j++) {
                        sb.append(lines.get(j)).append('\n');
                    }
                    return sb.toString();
                }
            }
        } catch (IOException e) {
            // ignore — snippet is optional
        }
        return null;
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
