package com.vulntriage.filter;

import java.util.List;

/**
 * Recursive descent parser for the filter rule language.
 * Consumes the token list produced by the Tokenizer and builds an
 * Expression AST respecting operator precedence:
 *   Precedence (highest to lowest):
 *     1. NOT       (unary, right-associative)
 *     2. AND       (binary, left-associative)
 *     3. OR        (binary, left-associative)
 *     4. Grouping  (parentheses reset precedence)
 * Grammar (EBNF):

 *   expression  ::= or_expr
 *   or_expr     ::= and_expr  ( OR  and_expr  )*
 *   and_expr    ::= not_expr  ( AND not_expr  )*
 *   not_expr    ::= NOT not_expr | primary
 *   primary     ::= LPAREN expression RPAREN | comparison
 *   comparison  ::= IDENTIFIER operator IDENTIFIER
 *   operator    ::= = | != | >= | <= | > | <
 * Example parse trace for "severity >= WARNING AND category = xss":
 *   or_expr
 *   └── and_expr
 *       ├── not_expr → primary → comparison(severity, >=, WARNING)
 *       └── not_expr → primary → comparison(category, =, xss)
 *   result: AndExpression(
 *              ComparisonExpression(severity, >=, WARNING),
 *              ComparisonExpression(category, =, xss)
 *           )
 */
public class FilterParser {

    private List<Token> tokens;
    private int         pos;

    /**
     * Parse the token list into an Expression AST.
     *
     * @param tokens token list from Tokenizer (must end with EOF)
     * @return root Expression node
     * @throws FilterException if the token sequence is syntactically invalid
     */
    public Expression parse(List<Token> tokens) {
        this.tokens = tokens;
        this.pos    = 0;
        Expression result = parseOr();
        expect(TokenType.EOF);
        return result;
    }

    // ── Grammar rules ──────────────────────────────────────────────────────

    /** or_expr ::= and_expr ( OR and_expr )* */
    private Expression parseOr() {
        Expression left = parseAnd();
        while (peek().getType() == TokenType.OR) {
            consume(); // eat OR
            Expression right = parseAnd();
            left = new OrExpression(left, right);
        }
        return left;
    }

    /** and_expr ::= not_expr ( AND not_expr )* */
    private Expression parseAnd() {
        Expression left = parseNot();
        while (peek().getType() == TokenType.AND) {
            consume(); // eat AND
            Expression right = parseNot();
            left = new AndExpression(left, right);
        }
        return left;
    }

    /** not_expr ::= NOT not_expr | primary */
    private Expression parseNot() {
        if (peek().getType() == TokenType.NOT) {
            consume(); // eat NOT
            Expression operand = parseNot(); // right-associative
            return new NotExpression(operand);
        }
        return parsePrimary();
    }

    /** primary ::= LPAREN expression RPAREN | comparison */
    private Expression parsePrimary() {
        if (peek().getType() == TokenType.LPAREN) {
            consume(); // eat (
            Expression inner = parseOr(); // full expression inside parens
            expect(TokenType.RPAREN);     // eat )
            return inner;
        }
        return parseComparison();
    }

    /** comparison ::= IDENTIFIER operator IDENTIFIER */
    private Expression parseComparison() {
        Token field = expect(TokenType.IDENTIFIER);
        Token op    = expectOperator();
        Token value = expect(TokenType.IDENTIFIER);
        return new ComparisonExpression(field.getValue(), op.getType(), value.getValue());
    }

    // ── Token consumption helpers ──────────────────────────────────────────

    /** Return the current token without consuming it. */
    private Token peek() {
        return tokens.get(pos);
    }

    /** Consume and return the current token. */
    private Token consume() {
        return tokens.get(pos++);
    }

    /**
     * Consume the current token, asserting it has the expected type.
     * Throws FilterException if the type does not match.
     */
    private Token expect(TokenType type) {
        Token token = peek();
        if (token.getType() != type) {
            throw new FilterException(
                "Expected " + type + " but got " + token.getType()
                + " ('" + token.getValue() + "') at position " + pos);
        }
        return consume();
    }

    /**
     * Consume the current token, asserting it is a comparison operator.
     * Throws FilterException if it is not an operator.
     */
    private Token expectOperator() {
        Token token = peek();
        return switch (token.getType()) {
            case EQ, NEQ, GTE, LTE, GT, LT -> consume();
            default -> throw new FilterException(
                "Expected comparison operator (=, !=, >=, <=, >, <) "
                + "but got '" + token.getValue() + "' at position " + pos);
        };
    }
}
