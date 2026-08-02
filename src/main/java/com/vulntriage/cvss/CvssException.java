package com.vulntriage.cvss;

/**
 * Thrown when a CVSS v3.1 vector string is malformed or contains an unknown token.
 *
 * The offending token is preserved so the caller can surface a precise diagnostic
 * message (e.g. "unknown metric 'XX'" or "invalid value 'Z' for metric AV").
 */
public class CvssException extends RuntimeException {

    private final String offendingToken;

    public CvssException(String message, String offendingToken) {
        super(message + " (token: '" + offendingToken + "')");
        this.offendingToken = offendingToken;
    }

    /** The specific part of the vector string that caused the failure. */
    public String getOffendingToken() {
        return offendingToken;
    }
}
