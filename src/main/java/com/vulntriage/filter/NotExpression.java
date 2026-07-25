package com.vulntriage.filter;

import com.vulntriage.domain.Finding;

/**
 * Unary NOT node — negates its child expression.
 */
public class NotExpression implements Expression {

    private final Expression operand;

    public NotExpression(Expression operand) {
        this.operand = operand;
    }

    @Override
    public boolean evaluate(Finding finding) {
        return !operand.evaluate(finding);
    }

    @Override
    public String describe() {
        return "(NOT " + operand.describe() + ")";
    }
}
