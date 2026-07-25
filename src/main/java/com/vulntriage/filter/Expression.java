package com.vulntriage.filter;

import com.vulntriage.domain.Finding;

/**
 * Abstract Syntax Tree node interface.
 * Each node in the AST implements this interface and knows how to
 * evaluate itself against a Finding, returning true or false.
 * The tree is built bottom-up by the FilterParser:
 *   severity >= WARNING AND category = xss
 *   AndExpression
 *   ├── ComparisonExpression(severity, >=, WARNING)
 *   └── ComparisonExpression(category, =, xss)
 * Evaluation is recursive: each composite node evaluates its children
 * and combines their results.
 */
public interface Expression {


    boolean evaluate(Finding finding);

    String describe();
}
