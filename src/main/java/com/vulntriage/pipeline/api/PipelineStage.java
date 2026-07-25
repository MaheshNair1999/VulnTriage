package com.vulntriage.pipeline.api;

/**
 * Chain of Responsibility pattern — each pipeline stage implements this interface.
 *
 * A stage receives the shared PipelineContext, does its work, and either:
 *   a) calls next.execute(ctx) to pass control to the next stage, or
 *   b) calls ctx.fail(reason) and returns early if something went wrong.
 *
 * The orchestrator assembles stages into a chain at startup.
 * Adding a new stage = write a class, register it in PipelineOrchestrator.
 */
public interface PipelineStage {

    /**
     * Execute this stage.
     *
     * @param ctx shared pipeline context — read inputs, write outputs
     */
    void execute(PipelineContext ctx);

    /**
     * Set the next stage in the chain.
     * Called by PipelineOrchestrator during assembly.
     */
    void setNext(PipelineStage next);

    /**
     * Human-readable name for this stage — used in log messages and UI.
     */
    String getName();
}
