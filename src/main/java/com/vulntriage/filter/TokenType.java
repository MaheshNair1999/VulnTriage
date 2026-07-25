package com.vulntriage.filter;

/**
 * Token types produced by the Tokenizer and consumed by the FilterParser.
 *
 * The filter rule engine supports expressions like:
 *   severity >= WARNING AND category = xss
 *   (severity = ERROR OR category = sql_injection) AND NOT source = TRIVY
 */
public enum TokenType {
    // Identifiers and literals
    IDENTIFIER,   // severity, category, source, WARNING, xss, etc.

    // Comparison operators
    EQ,           // =
    NEQ,          // !=
    GTE,          // >=
    LTE,          // <=
    GT,           // >
    LT,           // <

    // Logical operators
    AND,          // AND
    OR,           // OR
    NOT,          // NOT

    // Grouping
    LPAREN,       // (
    RPAREN,       // )

    // Control
    EOF           // end of input
}
