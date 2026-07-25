package com.vulntriage.filter;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexical analyser (tokenizer/lexer) for the filter rule language.

 * Converts a raw expression string into a flat list of Token objects.
 * The FilterParser then reads this token list to build the AST.

 * Supported input:
 *   severity >= WARNING AND category = xss
 *   (severity = ERROR OR category = sql_injection) AND NOT source = TRIVY

 * Tokenisation rules:
 *   - Whitespace is skipped
 *   - Identifiers are alphanumeric sequences (including underscore)
 *   - AND, OR, NOT are reserved keywords (case-insensitive)
 *   - Operators: =, !=, >=, <=, >, <
 *   - Parentheses: ( )
 */
public class Tokenizer {

    /**
     * Tokenise the given expression string.
     *
     * @param expression the raw filter expression
     * @return ordered list of tokens, ending with EOF
     * @throws FilterException if an unexpected character is encountered
     */
    public List<Token> tokenize(String expression) {
        List<Token> tokens = new ArrayList<>();
        int         i      = 0;
        int         len    = expression.length();

        while (i < len) {
            char c = expression.charAt(i);

            // Skip whitespace
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // Parentheses
            if (c == '(') { tokens.add(new Token(TokenType.LPAREN, "(")); i++; continue; }
            if (c == ')') { tokens.add(new Token(TokenType.RPAREN, ")")); i++; continue; }

            // Two-character operators (must check before single-char)
            if (i + 1 < len) {
                String two = expression.substring(i, i + 2);
                if (two.equals(">=")) { tokens.add(new Token(TokenType.GTE, ">=")); i += 2; continue; }
                if (two.equals("<=")) { tokens.add(new Token(TokenType.LTE, "<=")); i += 2; continue; }
                if (two.equals("!=")) { tokens.add(new Token(TokenType.NEQ, "!=")); i += 2; continue; }
            }

            // Single-character operators
            if (c == '=') { tokens.add(new Token(TokenType.EQ, "="));  i++; continue; }
            if (c == '>') { tokens.add(new Token(TokenType.GT, ">"));  i++; continue; }
            if (c == '<') { tokens.add(new Token(TokenType.LT, "<"));  i++; continue; }

            // Identifiers and keywords
            if (Character.isLetterOrDigit(c) || c == '_') {
                StringBuilder sb = new StringBuilder();
                while (i < len && (Character.isLetterOrDigit(expression.charAt(i))
                        || expression.charAt(i) == '_')) {
                    sb.append(expression.charAt(i));
                    i++;
                }
                String word = sb.toString();

                // Classify reserved keywords (case-insensitive)
                tokens.add(switch (word.toUpperCase()) {
                    case "AND" -> new Token(TokenType.AND, word);
                    case "OR"  -> new Token(TokenType.OR,  word);
                    case "NOT" -> new Token(TokenType.NOT, word);
                    default    -> new Token(TokenType.IDENTIFIER, word);
                });
                continue;
            }

            throw new FilterException(
                "Unexpected character '" + c + "' at position " + i
                + " in expression: " + expression);
        }

        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }
}
