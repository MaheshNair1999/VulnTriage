package com.vulntriage.filter;

import com.vulntriage.domain.Finding;

/**
 * Composite AST nodes for logical operators.
 * Each class is a separate file logically but kept together here for compactness.
 */

// ── AndExpression ──────────────────────────────────────────────────────────

class AndExpression implements Expression {

    private final Expression left;
    private final Expression right;

    AndExpression(Expression left, Expression right) {
        this.left  = left;
        this.right = right;
    }

    @Override
    public boolean evaluate(Finding finding) {
        // Short-circuit: if left is false, right is never evaluated
        return left.evaluate(finding) && right.evaluate(finding);
    }

    @Override
    public String describe() {
        return "(" + left.describe() + " AND " + right.describe() + ")";
    }
}
