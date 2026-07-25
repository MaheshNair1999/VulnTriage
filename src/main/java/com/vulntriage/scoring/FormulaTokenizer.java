package com.vulntriage.scoring;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexical analyser for arithmetic formula strings.
 *
 * Recognises: numeric literals (integer and decimal), identifiers
 * (variable names), the four arithmetic operators (+  -  *  /),
 * and parentheses. Whitespace is ignored. Unknown characters throw
 * FormulaException with the offending position.
 */
public class FormulaTokenizer {

    public List<FormulaToken> tokenize(String formula) {
        List<FormulaToken> tokens = new ArrayList<>();
        int i = 0, len = formula.length();

        while (i < len) {
            char c = formula.charAt(i);

            if (Character.isWhitespace(c)) { i++; continue; }

            switch (c) {
                case '+' -> { tokens.add(new FormulaToken(FormulaTokenType.PLUS,     "+")); i++; }
                case '-' -> { tokens.add(new FormulaToken(FormulaTokenType.MINUS,    "-")); i++; }
                case '*' -> { tokens.add(new FormulaToken(FormulaTokenType.MULTIPLY, "*")); i++; }
                case '/' -> { tokens.add(new FormulaToken(FormulaTokenType.DIVIDE,   "/")); i++; }
                case '(' -> { tokens.add(new FormulaToken(FormulaTokenType.LPAREN,   "(")); i++; }
                case ')' -> { tokens.add(new FormulaToken(FormulaTokenType.RPAREN,   ")")); i++; }
                default  -> {
                    if (Character.isDigit(c) || c == '.') {
                        StringBuilder sb = new StringBuilder();
                        boolean hasDot = false;
                        while (i < len && (Character.isDigit(formula.charAt(i))
                                           || (formula.charAt(i) == '.' && !hasDot))) {
                            if (formula.charAt(i) == '.') hasDot = true;
                            sb.append(formula.charAt(i++));
                        }
                        tokens.add(new FormulaToken(FormulaTokenType.NUMBER, sb.toString()));
                    } else if (Character.isLetter(c) || c == '_') {
                        StringBuilder sb = new StringBuilder();
                        while (i < len && (Character.isLetterOrDigit(formula.charAt(i))
                                           || formula.charAt(i) == '_')) {
                            sb.append(formula.charAt(i++));
                        }
                        tokens.add(new FormulaToken(FormulaTokenType.IDENTIFIER, sb.toString()));
                    } else {
                        throw new FormulaException(
                            "Unexpected character '" + c + "' at position " + i);
                    }
                }
            }
        }

        tokens.add(new FormulaToken(FormulaTokenType.EOF, ""));
        return tokens;
    }
}
