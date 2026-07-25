package com.vulntriage.pipeline;

public class WorkflowParseException extends RuntimeException {
    public WorkflowParseException(String message)                  { super(message); }
    public WorkflowParseException(String message, Throwable cause) { super(message, cause); }
}
