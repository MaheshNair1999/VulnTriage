package com.vulntriage.scanner;

import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerException;
import com.vulntriage.scanner.semgrep.SemgrepOutputParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemgrepOutputParserTest {

    private SemgrepOutputParser parser;

    @BeforeEach
    void setup() {
        parser = new SemgrepOutputParser();
    }

    // ── Realistic Semgrep JSON ─────────────────────────────────────────────

    private static final String SAMPLE_JSON = """
        {
          "results": [
            {
              "check_id": "python.django.security.audit.xss-safe-filter-escape",
              "path": "app/templates/dashboard.html",
              "start": { "line": 45, "col": 1 },
              "end":   { "line": 45, "col": 30 },
              "extra": {
                "message": "Detected user input marked as safe without sanitisation.",
                "severity": "WARNING",
                "lines": "{{ user_input|safe }}",
                "metadata": { "category": "security" }
              }
            },
            {
              "check_id": "python.django.security.audit.sql-injection",
              "path": "app/queries.py",
              "start": { "line": 102, "col": 5 },
              "end":   { "line": 102, "col": 60 },
              "extra": {
                "message": "SQL injection via string concatenation.",
                "severity": "ERROR",
                "lines": "cursor.execute('SELECT * FROM users WHERE id=' + user_id)",
                "metadata": {}
              }
            }
          ],
          "errors": []
        }
        """;

    @Test
    void parse_returnsCorrectNumberOfFindings() {
        List<RawFinding> findings = parser.parse(SAMPLE_JSON);
        assertEquals(2, findings.size());
    }

    @Test
    void parse_firstFinding_hasCorrectFields() {
        RawFinding f = parser.parse(SAMPLE_JSON).get(0);

        assertEquals("SEMGREP",    f.getSource());
        assertEquals("python.django.security.audit.xss-safe-filter-escape", f.getRuleId());
        assertEquals("app/templates/dashboard.html", f.getFilePath());
        assertEquals(45,           f.getLineNumber());
        assertEquals("WARNING",    f.getSeverity());
        assertEquals("{{ user_input|safe }}", f.getCodeSnippet());
        assertNotNull(f.getMessage());
        assertFalse(f.getMessage().isBlank());
    }

    @Test
    void parse_secondFinding_severityIsError() {
        RawFinding f = parser.parse(SAMPLE_JSON).get(1);
        assertEquals("ERROR", f.getSeverity());
    }

    @Test
    void parse_categoryDerivedFromRuleId_whenMetadataMissing() {
        // Second finding has empty metadata, so category comes from rule ID
        RawFinding f = parser.parse(SAMPLE_JSON).get(1);
        assertEquals("sql_injection", f.getCategory());
    }

    @Test
    void parse_emptyResults_returnsEmptyList() {
        String json = """
            { "results": [], "errors": [] }
            """;
        List<RawFinding> findings = parser.parse(json);
        assertTrue(findings.isEmpty());
    }

    @Test
    void parse_noResultsKey_returnsEmptyList() {
        String json = """
            { "errors": [] }
            """;
        List<RawFinding> findings = parser.parse(json);
        assertTrue(findings.isEmpty());
    }

    @Test
    void parse_invalidJson_throwsScannerException() {
        assertThrows(ScannerException.class, () -> parser.parse("not valid json {{"));
    }

    @Test
    void deriveCategoryFromRuleId_xss() {
        assertEquals("xss", SemgrepOutputParser.deriveCategoryFromRuleId(
            "python.django.security.audit.xss-safe-filter"));
    }

    @Test
    void deriveCategoryFromRuleId_csrf() {
        assertEquals("csrf", SemgrepOutputParser.deriveCategoryFromRuleId(
            "python.flask.security.audit.csrf-protection-missing"));
    }

    @Test
    void deriveCategoryFromRuleId_unknown() {
        assertEquals("security", SemgrepOutputParser.deriveCategoryFromRuleId(
            "python.misc.audit.some-other-rule"));
    }

    @Test
    void deriveCategoryFromRuleId_null_returnsUnknown() {
        assertEquals("unknown", SemgrepOutputParser.deriveCategoryFromRuleId(null));
    }
}
