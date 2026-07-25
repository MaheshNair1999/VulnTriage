package com.vulntriage.filter;

import com.vulntriage.domain.Finding;

/**
 * Binary OR node — true if either child evaluates to true.
 * Uses short-circuit evaluation: if left is true, right is not evaluated.
 */
public class OrExpression implements Expression {

    private final Expression left;
    private final Expression right;

    public OrExpression(Expression left, Expression right) {
        this.left  = left;
        this.right = right;
    }

    @Override
    public boolean evaluate(Finding finding) {
        return left.evaluate(finding) || right.evaluate(finding);
    }

    @Override
    public String describe() {
        return "(" + left.describe() + " OR " + right.describe() + ")";
    }
}
