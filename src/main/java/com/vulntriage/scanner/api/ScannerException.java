package com.vulntriage.scanner.api;

/**
 * Thrown when a scanner binary fails, times out, or produces unparseable output.
 * Wraps the underlying cause so callers can log or display a meaningful message.
 */
public class ScannerException extends RuntimeException {

    public ScannerException(String message) {
        super(message);
    }

    public ScannerException(String message, Throwable cause) {
        super(message, cause);
    }
}
