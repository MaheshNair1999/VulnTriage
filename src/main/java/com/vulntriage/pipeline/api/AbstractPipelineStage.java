package com.vulntriage.pipeline.api;

import com.vulntriage.event.PipelineEvent;
import com.vulntriage.event.PipelineObserver;

import java.util.List;

/**
 * Base class for all pipeline stages.
 * Handles the next-stage reference and observer notification boilerplate
 * so concrete stages only need to implement doExecute().
 */
public abstract class AbstractPipelineStage implements PipelineStage {

    protected PipelineStage       next;
    protected List<PipelineObserver> observers;

    public AbstractPipelineStage(List<PipelineObserver> observers) {
        this.observers = observers;
    }

    @Override
    public void execute(PipelineContext ctx) {
        if (ctx.hasFailed()) return;  // short-circuit if a previous stage failed

        emit(PipelineEvent.stageStarted(getName()));
        try {
            doExecute(ctx);
        } catch (Exception e) {
            ctx.fail(getName() + " failed: " + e.getMessage());
            emit(PipelineEvent.failed(ctx.getErrorMessage()));
            return;
        }

        if (!ctx.hasFailed()) {
            emit(PipelineEvent.stageCompleted(getName(), progressAfter()));
            if (next != null) {
                next.execute(ctx);
            }
        }
    }

    @Override
    public void setNext(PipelineStage next) {
        this.next = next;
    }

    // ── Template methods ───────────────────────────────────────────────────

    /** Subclasses implement their actual logic here. */
    protected abstract void doExecute(PipelineContext ctx);

    /**
     * Overall pipeline progress (0-100) after this stage completes.
     * Override in concrete stages to set a meaningful value.
     */
    protected int progressAfter() { return -1; }

    // ── Observer notification ──────────────────────────────────────────────

    protected void emit(PipelineEvent event) {
        if (observers != null) {
            observers.forEach(o -> o.onEvent(event));
        }
    }
}
