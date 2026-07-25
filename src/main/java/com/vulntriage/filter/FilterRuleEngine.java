package com.vulntriage.filter;

import com.vulntriage.domain.Finding;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Public API for the filter rule engine.
 *
 * Usage:
 *   FilterRuleEngine engine = new FilterRuleEngine();
 *
 *   // Compile once, evaluate many times
 *   Expression expr = engine.compile("severity >= WARNING AND category = xss");
 *   List<Finding> filtered = engine.apply(findings, expr);
 *
 *   // Or compile and apply in one call
 *   List<Finding> filtered = engine.filter(findings, "severity >= WARNING AND category = xss");
 *
 * The engine compiles the expression string into an AST on the first call.
 * Compiled expressions are reusable and thread-safe (each Finding is
 * evaluated independently with no shared mutable state).
 *
 * Supported fields: severity, category, source, rule_id, file
 * Supported operators: =, !=, >=, <=, >, <
 * Supported logical: AND, OR, NOT, ( )
 *
 * Severity ordering for >= / <=: INFO < WARNING < ERROR
 */
public class FilterRuleEngine {

    private final Tokenizer    tokenizer = new Tokenizer();
    private final FilterParser parser    = new FilterParser();

    /**
     * Compile an expression string into a reusable AST.
     *
     * @param expression filter expression string
     * @return compiled Expression ready for evaluation
     * @throws FilterException if the expression is syntactically invalid
     */
    public Expression compile(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new FilterException("Filter expression cannot be empty");
        }
        List<Token> tokens = tokenizer.tokenize(expression);
        return parser.parse(tokens);
    }

    /**
     * Filter a list of findings using a pre-compiled expression.
     *
     * @param findings   findings to filter
     * @param expression compiled expression from compile()
     * @return findings for which the expression evaluates to true
     */
    public List<Finding> apply(List<Finding> findings, Expression expression) {
        return findings.stream()
            .filter(expression::evaluate)
            .collect(Collectors.toList());
    }

    /**
     * Compile and apply in one call — convenience method.
     *
     * @param findings   findings to filter
     * @param expression filter expression string
     * @return filtered findings
     * @throws FilterException if the expression is invalid
     */
    public List<Finding> filter(List<Finding> findings, String expression) {
        Expression compiled = compile(expression);
        return apply(findings, compiled);
    }

    /**
     * Validate an expression string without applying it.
     * Returns true if the expression compiles without errors.
     */
    public boolean isValid(String expression) {
        try {
            compile(expression);
            return true;
        } catch (FilterException e) {
            return false;
        }
    }

    /**
     * Return a human-readable description of a compiled expression.
     * Useful for displaying the active filter in the UI.
     */
    public String describe(Expression expression) {
        return expression.describe();
    }
}
