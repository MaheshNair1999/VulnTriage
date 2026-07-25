package com.vulntriage.scanner;

import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerException;
import com.vulntriage.scanner.trivy.TrivyOutputParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrivyOutputParserTest {

    private TrivyOutputParser parser;

    @BeforeEach
    void setup() {
        parser = new TrivyOutputParser();
    }

    private static final String SAMPLE_JSON = """
        {
          "SchemaVersion": 2,
          "Results": [
            {
              "Target": "requirements.txt",
              "Class": "lang-pkgs",
              "Type": "pip",
              "Vulnerabilities": [
                {
                  "VulnerabilityID": "CVE-2023-43665",
                  "PkgName": "django",
                  "InstalledVersion": "3.2.0",
                  "FixedVersion": "3.2.23",
                  "Severity": "HIGH",
                  "Title": "Django denial-of-service vulnerability",
                  "Description": "In Django 3.2 before 3.2.23..."
                },
                {
                  "VulnerabilityID": "CVE-2023-41164",
                  "PkgName": "django",
                  "InstalledVersion": "3.2.0",
                  "FixedVersion": "3.2.22",
                  "Severity": "MEDIUM",
                  "Title": "Potential denial of service vulnerability in django.utils.encoding.uri_to_iri",
                  "Description": "In Django 3.2 before 3.2.22..."
                }
              ]
            },
            {
              "Target": "frontend/package-lock.json",
              "Class": "lang-pkgs",
              "Type": "npm",
              "Vulnerabilities": null
            }
          ]
        }
        """;

    @Test
    void parse_returnsCorrectNumberOfFindings() {
        List<RawFinding> findings = parser.parse(SAMPLE_JSON);
        // 2 from requirements.txt, 0 from package-lock (null vulnerabilities)
        assertEquals(2, findings.size());
    }

    @Test
    void parse_firstFinding_hasCorrectFields() {
        RawFinding f = parser.parse(SAMPLE_JSON).get(0);

        assertEquals("TRIVY",               f.getSource());
        assertEquals("CVE-2023-43665",      f.getRuleId());
        assertEquals("CVE-2023-43665",      f.getCveId());
        assertEquals("requirements.txt",    f.getFilePath());
        assertNull(f.getLineNumber()); // Trivy has no line number
        assertEquals("django",              f.getPackageName());
        assertEquals("3.2.0",              f.getInstalledVersion());
        assertEquals("3.2.23",             f.getFixedVersion());
        assertEquals("dependency",          f.getCategory());
    }

    @Test
    void parse_highSeverity_mapsToError() {
        RawFinding f = parser.parse(SAMPLE_JSON).get(0);
        assertEquals("ERROR", f.getSeverity()); // HIGH → ERROR
    }

    @Test
    void parse_mediumSeverity_mapsToWarning() {
        RawFinding f = parser.parse(SAMPLE_JSON).get(1);
        assertEquals("WARNING", f.getSeverity()); // MEDIUM → WARNING
    }

    @Test
    void parse_nullVulnerabilities_skippedGracefully() {
        // The second result has "Vulnerabilities": null — should produce 0 findings
        List<RawFinding> findings = parser.parse(SAMPLE_JSON);
        assertEquals(2, findings.size()); // only from requirements.txt
    }

    @Test
    void parse_emptyResults_returnsEmptyList() {
        String json = """
            { "Results": [] }
            """;
        assertTrue(parser.parse(json).isEmpty());
    }

    @Test
    void parse_invalidJson_throwsScannerException() {
        assertThrows(ScannerException.class, () -> parser.parse("{ bad json "));
    }

    @Test
    void mapSeverity_critical_returnsError() {
        assertEquals("ERROR", TrivyOutputParser.mapSeverity("CRITICAL"));
    }

    @Test
    void mapSeverity_low_returnsInfo() {
        assertEquals("INFO", TrivyOutputParser.mapSeverity("LOW"));
    }

    @Test
    void mapSeverity_unknown_returnsInfo() {
        assertEquals("INFO", TrivyOutputParser.mapSeverity("UNKNOWN"));
    }

    @Test
    void mapSeverity_null_returnsInfo() {
        assertEquals("INFO", TrivyOutputParser.mapSeverity(null));
    }
}
