package com.vulntriage.filter;

/**
 * A single lexical token produced by the Tokenizer.
 * Immutable — created once during tokenisation and not modified.
 */
public class Token {

    private final TokenType type;
    private final String    value;

    public Token(TokenType type, String value) {
        this.type  = type;
        this.value = value;
    }

    public TokenType getType()  { return type; }
    public String    getValue() { return value; }

    @Override
    public String toString() {
        return "Token(" + type + ", '" + value + "')";
    }
}
