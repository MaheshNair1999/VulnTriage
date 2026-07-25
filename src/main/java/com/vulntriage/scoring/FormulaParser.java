package com.vulntriage.scoring;

import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for arithmetic formula strings.
 *
 * Grammar (operator precedence: unary minus > * / > + -):
 *
 *   expr   ::= term   ( ( '+' | '-' ) term   )*
 *   term   ::= factor ( ( '*' | '/' ) factor )*
 *   factor ::= NUMBER | IDENTIFIER | '(' expr ')' | '-' factor
 *
 * Produces a FormulaNode tree that can be evaluated with a variable map.
 */
public class FormulaParser {

    private List<FormulaToken> tokens;
    private int pos;

    public FormulaNode parse(List<FormulaToken> tokens) {
        this.tokens = tokens;
        this.pos    = 0;
        FormulaNode result = parseExpr();
        expect(FormulaTokenType.EOF);
        return result;
    }

    // expr ::= term ( ( '+' | '-' ) term )*
    private FormulaNode parseExpr() {
        FormulaNode left = parseTerm();
        while (peek().getType() == FormulaTokenType.PLUS
            || peek().getType() == FormulaTokenType.MINUS) {
            String op = consume().getValue();
            left = new BinaryNode(op, left, parseTerm());
        }
        return left;
    }

    // term ::= factor ( ( '*' | '/' ) factor )*
    private FormulaNode parseTerm() {
        FormulaNode left = parseFactor();
        while (peek().getType() == FormulaTokenType.MULTIPLY
            || peek().getType() == FormulaTokenType.DIVIDE) {
            String op = consume().getValue();
            left = new BinaryNode(op, left, parseFactor());
        }
        return left;
    }

    // factor ::= NUMBER | IDENTIFIER | '(' expr ')' | '-' factor
    private FormulaNode parseFactor() {
        FormulaToken t = peek();
        if (t.getType() == FormulaTokenType.NUMBER) {
            consume();
            return new NumberNode(Double.parseDouble(t.getValue()));
        }
        if (t.getType() == FormulaTokenType.IDENTIFIER) {
            consume();
            return new VariableNode(t.getValue());
        }
        if (t.getType() == FormulaTokenType.LPAREN) {
            consume();
            FormulaNode inner = parseExpr();
            expect(FormulaTokenType.RPAREN);
            return inner;
        }
        if (t.getType() == FormulaTokenType.MINUS) {
            consume();
            return new UnaryMinusNode(parseFactor());
        }
        throw new FormulaException(
            "Unexpected token '" + t.getValue() + "' at position " + pos);
    }

    private FormulaToken peek() {
        return tokens.get(pos);
    }

    private FormulaToken consume() {
        return tokens.get(pos++);
    }

    private void expect(FormulaTokenType type) {
        FormulaToken t = consume();
        if (t.getType() != type) {
            throw new FormulaException(
                "Expected " + type + " but got " + t.getType() + " ('" + t.getValue() + "')");
        }
    }

    // ── AST node implementations ───────────────────────────────────────────

    static class NumberNode implements FormulaNode {
        private final double value;
        NumberNode(double value) { this.value = value; }

        @Override public double evaluate(Map<String, Double> vars) { return value; }
        @Override public String describe() { return String.valueOf(value); }
    }

    static class VariableNode implements FormulaNode {
        private final String name;
        VariableNode(String name) { this.name = name; }

        @Override
        public double evaluate(Map<String, Double> vars) {
            if (!vars.containsKey(name))
                throw new FormulaException("Unknown variable '" + name + "'");
            return vars.get(name);
        }
        @Override public String describe() { return name; }
    }

    static class BinaryNode implements FormulaNode {
        private final String     operator;
        private final FormulaNode left, right;

        BinaryNode(String operator, FormulaNode left, FormulaNode right) {
            this.operator = operator;
            this.left     = left;
            this.right    = right;
        }

        @Override
        public double evaluate(Map<String, Double> vars) {
            double l = left.evaluate(vars);
            double r = right.evaluate(vars);
            return switch (operator) {
                case "+" -> l + r;
                case "-" -> l - r;
                case "*" -> l * r;
                case "/" -> {
                    if (r == 0) throw new FormulaException("Division by zero");
                    yield l / r;
                }
                default -> throw new FormulaException("Unknown operator '" + operator + "'");
            };
        }

        @Override
        public String describe() {
            return "(" + left.describe() + " " + operator + " " + right.describe() + ")";
        }
    }

    static class UnaryMinusNode implements FormulaNode {
        private final FormulaNode operand;
        UnaryMinusNode(FormulaNode operand) { this.operand = operand; }

        @Override public double evaluate(Map<String, Double> vars) { return -operand.evaluate(vars); }
        @Override public String describe() { return "(-" + operand.describe() + ")"; }
    }
}
