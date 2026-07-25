package com.vulntriage.pipeline;

import com.vulntriage.config.ScanConfig;
import com.vulntriage.domain.Repository;
import com.vulntriage.domain.enums.PipelineStatus;
import com.vulntriage.event.PipelineEvent;
import com.vulntriage.event.PipelineObserver;
import com.vulntriage.event.ProgressLogger;
import com.vulntriage.pipeline.api.PipelineContext;
import com.vulntriage.pipeline.api.PipelineStage;
import com.vulntriage.pipeline.stages.*;
import com.vulntriage.repository.api.*;
import com.vulntriage.repository.sqlite.*;
import com.vulntriage.triage.api.TriageStrategy;
import com.vulntriage.triage.mock.MockTriageStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the pipeline stages into a chain and runs them.
 *
 * This is the single entry point for triggering a full pipeline run.
 * It wires all dependencies (repositories, strategy, observers) and
 * hands off to the first stage, which calls the next, and so on.
 *
 * Usage:
 *   PipelineOrchestrator orchestrator = PipelineOrchestrator.builder()
 *       .strategy(new OllamaTriageStrategy("qwen3:8b"))
 *       .sampleSize(1000)
 *       .addObserver(new UiProgressUpdater())
 *       .build();
 *
 *   PipelineContext result = orchestrator.run(repository, ScanConfig.defaults());
 */
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final List<PipelineObserver> observers;
    private final TriageStrategy         strategy;
    private final int                    sampleSize;
    private final String                 runName;

    // Repositories — wired in builder
    private final ScanRunRepository      scanRunRepo;
    private final FindingRepository      findingRepo;
    private final EvaluationRepository   evalRepo;
    private final LlmResultRepository    llmRepo;
    private final ManualReviewRepository reviewRepo;

    private PipelineOrchestrator(Builder builder) {
        this.observers   = builder.observers;
        this.strategy    = builder.strategy;
        this.sampleSize  = builder.sampleSize;
        this.runName     = builder.runName;
        this.scanRunRepo = builder.scanRunRepo;
        this.findingRepo = builder.findingRepo;
        this.evalRepo    = builder.evalRepo;
        this.llmRepo     = builder.llmRepo;
        this.reviewRepo  = builder.reviewRepo;
    }

    /**
     * Run the full pipeline for the given repository.
     *
     * @return the completed PipelineContext (check hasFailed() for errors)
     */
    public PipelineContext run(Repository repository, ScanConfig config) {
        PipelineContext ctx = new PipelineContext(repository, config);
        ctx.setStatus(PipelineStatus.RUNNING);

        emit(PipelineEvent.started("Scanning " + repository.getName()));

        // ── Assemble chain ─────────────────────────────────────────────────
        PipelineStage scan     = new ScanStage    (observers, scanRunRepo, findingRepo);
        PipelineStage sample   = new SampleStage  (observers, sampleSize);
        PipelineStage triage   = new TriageStage  (observers, strategy, evalRepo, llmRepo, runName,
            com.vulntriage.config.AppConfig.getInstance().getTriageDelayMs());
        PipelineStage evaluate = new EvaluateStage(observers, evalRepo, llmRepo, reviewRepo);

        // Chain: scan → sample → triage → evaluate
        scan.setNext(sample);
        sample.setNext(triage);
        triage.setNext(evaluate);

        // ── Execute ────────────────────────────────────────────────────────
        try {
            scan.execute(ctx);
        } catch (Exception e) {
            ctx.fail("Unexpected pipeline error: " + e.getMessage());
            emit(PipelineEvent.failed(ctx.getErrorMessage()));
            log.error("Pipeline failed unexpectedly", e);
        }

        if (!ctx.hasFailed()) {
            ctx.setStatus(PipelineStatus.COMPLETE);
            emit(PipelineEvent.complete(
                "Pipeline complete. " + ctx.getAllFindings().size() + " findings, "
                + ctx.getSample().size() + " in sample."));
        }

        return ctx;
    }

    private void emit(PipelineEvent event) {
        observers.forEach(o -> o.onEvent(event));
    }

    // ── Builder ────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<PipelineObserver> observers  = new ArrayList<>();
        private TriageStrategy         strategy    = new MockTriageStrategy();
        private int                    sampleSize  = 1000;
        private String                 runName     = "Evaluation Run";
        private ScanRunRepository      scanRunRepo  = new SQLiteScanRunRepository();
        private FindingRepository      findingRepo  = new SQLiteFindingRepository();
        private EvaluationRepository   evalRepo     = new SQLiteEvaluationRepository();
        private LlmResultRepository    llmRepo      = new SQLiteLlmResultRepository();
        private ManualReviewRepository reviewRepo   = new SQLiteManualReviewRepository();

        public Builder() {
            // Always include the progress logger
            observers.add(new ProgressLogger());
        }

        public Builder strategy(TriageStrategy s)      { this.strategy   = s;    return this; }
        public Builder sampleSize(int n)               { this.sampleSize = n;    return this; }
        public Builder runName(String name)            { this.runName    = name; return this; }
        public Builder addObserver(PipelineObserver o) { observers.add(o);       return this; }

        // Allow overriding repos for testing
        public Builder scanRunRepo(ScanRunRepository r)     { this.scanRunRepo = r; return this; }
        public Builder findingRepo(FindingRepository r)     { this.findingRepo = r; return this; }
        public Builder evalRepo(EvaluationRepository r)     { this.evalRepo    = r; return this; }
        public Builder llmRepo(LlmResultRepository r)       { this.llmRepo     = r; return this; }
        public Builder reviewRepo(ManualReviewRepository r) { this.reviewRepo  = r; return this; }

        public PipelineOrchestrator build() {
            return new PipelineOrchestrator(this);
        }
    }
}
