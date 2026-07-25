package com.vulntriage.event;

/**
 * Represents a moment in the pipeline lifecycle.
 * Observers receive these events and react (log, update UI, write to DB).
 */
public class PipelineEvent {

    public enum Type {
        PIPELINE_STARTED,
        STAGE_STARTED,
        STAGE_COMPLETED,
        FINDING_PROCESSED,   // emitted per finding during normalisation
        PIPELINE_COMPLETE,
        PIPELINE_FAILED
    }

    private final Type   type;
    private final String message;
    private final int    progress;  // 0-100 overall progress estimate
    private final Object payload;   // optional attached data (e.g. a Finding)

    // ── Factory methods keep construction clean ────────────────────────────

    public static PipelineEvent started(String message) {
        return new PipelineEvent(Type.PIPELINE_STARTED, message, 0, null);
    }

    public static PipelineEvent stageStarted(String stageName) {
        return new PipelineEvent(Type.STAGE_STARTED, "Starting: " + stageName, -1, stageName);
    }

    public static PipelineEvent stageCompleted(String stageName, int progress) {
        return new PipelineEvent(Type.STAGE_COMPLETED, "Completed: " + stageName, progress, stageName);
    }

    public static PipelineEvent findingProcessed(int progress) {
        return new PipelineEvent(Type.FINDING_PROCESSED, "Processing findings...", progress, null);
    }

    public static PipelineEvent complete(String summary) {
        return new PipelineEvent(Type.PIPELINE_COMPLETE, summary, 100, null);
    }

    public static PipelineEvent failed(String reason) {
        return new PipelineEvent(Type.PIPELINE_FAILED, reason, -1, null);
    }

    // ── Constructor ────────────────────────────────────────────────────────

    private PipelineEvent(Type type, String message, int progress, Object payload) {
        this.type     = type;
        this.message  = message;
        this.progress = progress;
        this.payload  = payload;
    }

    // ── Getters ────────────────────────────────────────────────────────────

    public Type   getType()     { return type; }
    public String getMessage()  { return message; }
    public int    getProgress() { return progress; }
    public Object getPayload()  { return payload; }

    @Override
    public String toString() {
        return "[" + type + "] " + message
            + (progress >= 0 ? " (" + progress + "%)" : "");
    }
}
