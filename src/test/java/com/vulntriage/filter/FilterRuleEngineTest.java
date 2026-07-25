package com.vulntriage.filter;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Filter Rule Engine — Tokenizer + FilterParser + Expression evaluation.
 *
 * This is the most algorithmically complex component in the SE project.
 * Tests cover: simple comparisons, AND/OR/NOT combinations, operator precedence,
 * parenthesised expressions, severity ordering, and error cases.
 */
class FilterRuleEngineTest {

    private FilterRuleEngine engine;
    private Finding warningXss;
    private Finding errorSql;
    private Finding infoConfig;
    private Finding errorXss;

    @BeforeEach
    void setup() {
        engine = new FilterRuleEngine();

        warningXss  = finding(Severity.WARNING, "xss",           ScannerType.SEMGREP);
        errorSql    = finding(Severity.ERROR,   "sql_injection",  ScannerType.SEMGREP);
        infoConfig  = finding(Severity.INFO,    "configuration",  ScannerType.TRIVY);
        errorXss    = finding(Severity.ERROR,   "xss",            ScannerType.SEMGREP);
    }

    // ── Simple equality ────────────────────────────────────────────────────

    @Test
    void filter_categoryEquals_matchesCorrect() {
        var result = engine.filter(allFindings(), "category = xss");
        assertEquals(2, result.size());
        result.forEach(f -> assertEquals("xss", f.getCategory()));
    }

    @Test
    void filter_severityEquals_matchesCorrect() {
        var result = engine.filter(allFindings(), "severity = ERROR");
        assertEquals(2, result.size());
        result.forEach(f -> assertEquals(Severity.ERROR, f.getSeverity()));
    }

    @Test
    void filter_sourceEquals_matchesCorrect() {
        var result = engine.filter(allFindings(), "source = TRIVY");
        assertEquals(1, result.size());
        assertEquals(ScannerType.TRIVY, result.get(0).getSource());
    }

    // ── Severity ordering ──────────────────────────────────────────────────

    @Test
    void filter_severityGTE_warning_excludesInfo() {
        var result = engine.filter(allFindings(), "severity >= WARNING");
        assertEquals(3, result.size()); // WARNING + ERROR + ERROR
        result.forEach(f -> assertNotEquals(Severity.INFO, f.getSeverity()));
    }

    @Test
    void filter_severityGTE_error_onlyErrors() {
        var result = engine.filter(allFindings(), "severity >= ERROR");
        assertEquals(2, result.size());
        result.forEach(f -> assertEquals(Severity.ERROR, f.getSeverity()));
    }

    @Test
    void filter_severityLTE_warning_excludesError() {
        var result = engine.filter(allFindings(), "severity <= WARNING");
        assertEquals(2, result.size()); // INFO + WARNING
        result.forEach(f -> assertNotEquals(Severity.ERROR, f.getSeverity()));
    }

    // ── AND operator ───────────────────────────────────────────────────────

    @Test
    void filter_and_bothConditionsMustMatch() {
        var result = engine.filter(allFindings(), "severity = ERROR AND category = xss");
        assertEquals(1, result.size());
        assertEquals(Severity.ERROR, result.get(0).getSeverity());
        assertEquals("xss", result.get(0).getCategory());
    }

    @Test
    void filter_and_noMatch_returnsEmpty() {
        var result = engine.filter(allFindings(), "severity = INFO AND category = xss");
        assertTrue(result.isEmpty());
    }

    // ── OR operator ────────────────────────────────────────────────────────

    @Test
    void filter_or_eitherConditionSuffices() {
        var result = engine.filter(allFindings(), "category = xss OR category = sql_injection");
        assertEquals(3, result.size());
    }

    @Test
    void filter_or_bothMatch_noduplicates() {
        // severity >= WARNING is true for 3 findings, category = xss is true for 2
        // overlap: warningXss, errorXss, errorSql = 3 unique findings
        var result = engine.filter(allFindings(),
            "severity >= WARNING OR category = xss");
        assertEquals(3, result.size());
    }

    // ── NOT operator ───────────────────────────────────────────────────────

    @Test
    void filter_not_invertsSingleCondition() {
        var result = engine.filter(allFindings(), "NOT severity = INFO");
        assertEquals(3, result.size());
        result.forEach(f -> assertNotEquals(Severity.INFO, f.getSeverity()));
    }

    @Test
    void filter_not_withAnd() {
        var result = engine.filter(allFindings(),
            "severity >= WARNING AND NOT category = xss");
        assertEquals(1, result.size());
        assertEquals("sql_injection", result.get(0).getCategory());
    }

    // ── Operator precedence ────────────────────────────────────────────────

    @Test
    void filter_precedence_andBindsTighterThanOr() {
        // "A OR B AND C" should parse as "A OR (B AND C)"
        // category=xss OR (severity=ERROR AND source=TRIVY)
        // xss matches: warningXss, errorXss
        // ERROR AND TRIVY matches: none (infoConfig is TRIVY but INFO)
        var result = engine.filter(allFindings(),
            "category = xss OR severity = ERROR AND source = TRIVY");
        assertEquals(2, result.size());
    }

    @Test
    void filter_precedence_notBindsTightestOfAll() {
        // "NOT severity = INFO AND category = xss"
        // parses as "(NOT severity=INFO) AND category=xss"
        // not-INFO: warningXss, errorSql, errorXss (3)
        // AND category=xss: warningXss, errorXss (2)
        var result = engine.filter(allFindings(),
            "NOT severity = INFO AND category = xss");
        assertEquals(2, result.size());
    }

    // ── Parentheses ────────────────────────────────────────────────────────

    @Test
    void filter_parentheses_overrideDefaultPrecedence() {
        // "(category=xss OR severity=ERROR) AND source=SEMGREP"
        // (xss or error): warningXss, errorSql, errorXss (3)
        // AND semgrep: same 3 (infoConfig is TRIVY, already excluded)
        var result = engine.filter(allFindings(),
            "(category = xss OR severity = ERROR) AND source = SEMGREP");
        assertEquals(3, result.size());
    }

    @Test
    void filter_nestedParentheses_evaluateCorrectly() {
        var result = engine.filter(allFindings(),
            "NOT (severity = INFO OR category = sql_injection)");
        // excludes INFO (infoConfig) and sql_injection (errorSql)
        // remaining: warningXss, errorXss
        assertEquals(2, result.size());
    }

    // ── isValid ────────────────────────────────────────────────────────────

    @Test
    void isValid_validExpression_returnsTrue() {
        assertTrue(engine.isValid("severity >= WARNING AND category = xss"));
    }

    @Test
    void isValid_invalidExpression_returnsFalse() {
        assertFalse(engine.isValid("severity BADOP WARNING"));
    }

    @Test
    void isValid_empty_returnsFalse() {
        assertFalse(engine.isValid(""));
    }

    // ── describe ───────────────────────────────────────────────────────────

    @Test
    void describe_returnsReadableString() {
        Expression expr = engine.compile("severity >= WARNING AND category = xss");
        String desc = engine.describe(expr);
        assertFalse(desc.isBlank());
        assertTrue(desc.contains("severity"));
        assertTrue(desc.contains("category"));
    }

    // ── Error cases ────────────────────────────────────────────────────────

    @Test
    void compile_unknownField_doesNotThrow() {
        // Unknown fields return null from Finding — expression evaluates to false
        assertDoesNotThrow(() -> engine.filter(allFindings(), "unknownfield = value"));
    }

    @Test
    void compile_missingOperator_throwsFilterException() {
        assertThrows(FilterException.class, () -> engine.compile("severity WARNING"));
    }

    @Test
    void compile_missingValue_throwsFilterException() {
        assertThrows(FilterException.class, () -> engine.compile("severity ="));
    }

    @Test
    void compile_unclosedParen_throwsFilterException() {
        assertThrows(FilterException.class, () -> engine.compile("(severity = WARNING"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<Finding> allFindings() {
        return List.of(warningXss, errorSql, infoConfig, errorXss);
    }

    private Finding finding(Severity severity, String category, ScannerType source) {
        Finding f = new Finding();
        f.setSeverity   (severity);
        f.setCategory   (category);
        f.setSource     (source);
        f.setRuleId     ("test.rule." + category);
        f.setFilePath   ("app/views.py");
        f.setFingerprint("fp-" + severity + "-" + category + "-" + source);
        return f;
    }
}
