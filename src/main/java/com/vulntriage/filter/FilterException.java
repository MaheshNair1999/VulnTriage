package com.vulntriage.filter;

/**
 * Thrown when the Tokenizer or FilterParser encounters an invalid expression.
 */
public class FilterException extends RuntimeException {
    public FilterException(String message)                  { super(message); }
    public FilterException(String message, Throwable cause) { super(message, cause); }
}
