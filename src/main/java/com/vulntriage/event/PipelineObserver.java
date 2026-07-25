package com.vulntriage.event;

/**
 * Observer pattern — any component that wants to react to pipeline events
 * implements this interface and registers with the PipelineOrchestrator.
 *
 * Current implementations:
 *   - ProgressLogger      — writes events to SLF4J log
 *   - UiProgressUpdater   — pushes progress to the JavaFX UI thread
 *
 * Future implementations (easy to add):
 *   - DatabaseWriter      — persists scan run status to the DB
 *   - WebSocketEmitter    — pushes progress to a browser client
 */
@FunctionalInterface
public interface PipelineObserver {
    void onEvent(PipelineEvent event);
}
