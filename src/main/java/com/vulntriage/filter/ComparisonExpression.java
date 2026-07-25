package com.vulntriage.filter;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;

/**
 * Leaf node of the AST — compares one field of a Finding against a literal value.
 *
 * Supported fields:
 *   severity   — compared by ordinal (INFO < WARNING < ERROR) for >= and <=
 *   category   — string comparison
 *   source     — string comparison (SEMGREP, TRIVY)
 *   rule_id    — string contains check
 *   file       — string contains check
 *
 * Examples:
 *   severity >= WARNING
 *   category = xss
 *   source = SEMGREP
 */
public class ComparisonExpression implements Expression {

    private final String    field;
    private final TokenType operator;
    private final String    value;

    public ComparisonExpression(String field, TokenType operator, String value) {
        this.field    = field.toLowerCase();
        this.operator = operator;
        this.value    = value;
    }

    @Override
    public boolean evaluate(Finding finding) {
        String fieldValue = extractField(finding);
        if (fieldValue == null) return false;

        // Severity uses ordinal comparison for >, <, >=, <=
        if (field.equals("severity")) {
            return compareSeverity(fieldValue, value);
        }

        // All other fields use string equality / contains
        return compareString(fieldValue, value);
    }

    @Override
    public String describe() {
        return field + " " + operatorSymbol() + " " + value;
    }

    // ── Field extraction ───────────────────────────────────────────────────

    private String extractField(Finding f) {
        return switch (field) {
            case "severity" -> f.getSeverity() != null ? f.getSeverity().name() : null;
            case "category" -> f.getCategory();
            case "source"   -> f.getSource()   != null ? f.getSource().name()   : null;
            case "rule_id"  -> f.getRuleId();
            case "file"     -> f.getFilePath();
            default         -> null;
        };
    }

    // ── Severity comparison ────────────────────────────────────────────────

    /**
     * Severity levels mapped to integers for ordered comparison.
     * INFO=1, WARNING=2, ERROR=3
     */
    private boolean compareSeverity(String actual, String expected) {
        int actualOrd   = severityOrdinal(actual);
        int expectedOrd = severityOrdinal(expected);
        if (actualOrd < 0 || expectedOrd < 0) return false;

        return switch (operator) {
            case EQ  -> actualOrd == expectedOrd;
            case NEQ -> actualOrd != expectedOrd;
            case GTE -> actualOrd >= expectedOrd;
            case LTE -> actualOrd <= expectedOrd;
            case GT  -> actualOrd >  expectedOrd;
            case LT  -> actualOrd <  expectedOrd;
            default  -> false;
        };
    }

    private int severityOrdinal(String name) {
        return switch (name.toUpperCase()) {
            case "INFO"    -> 1;
            case "WARNING" -> 2;
            case "ERROR"   -> 3;
            default        -> -1;
        };
    }

    // ── String comparison ──────────────────────────────────────────────────

    private boolean compareString(String actual, String expected) {
        String a = actual.toLowerCase();
        String e = expected.toLowerCase();
        return switch (operator) {
            case EQ  -> a.equals(e);
            case NEQ -> !a.equals(e);
            // For string fields, >= and <= fall back to contains
            case GTE, LTE, GT, LT -> a.contains(e);
            default -> false;
        };
    }

    private String operatorSymbol() {
        return switch (operator) {
            case EQ  -> "=";
            case NEQ -> "!=";
            case GTE -> ">=";
            case LTE -> "<=";
            case GT  -> ">";
            case LT  -> "<";
            default  -> "?";
        };
    }
}
