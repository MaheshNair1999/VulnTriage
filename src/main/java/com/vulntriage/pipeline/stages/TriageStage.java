package com.vulntriage.pipeline.stages;

import com.vulntriage.domain.EvaluationRun;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.event.PipelineEvent;
import com.vulntriage.event.PipelineObserver;
import com.vulntriage.pipeline.api.AbstractPipelineStage;
import com.vulntriage.pipeline.api.PipelineContext;
import com.vulntriage.repository.api.EvaluationRepository;
import com.vulntriage.repository.api.LlmResultRepository;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.api.TriageStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Stage 3: Run LLM-assisted triage on the sampled findings.
 *
 * Uses the injected TriageStrategy (Strategy pattern) so the LLM backend
 * can be swapped without changing this stage.
 *
 * Creates an EvaluationRun to group all LLM results together.
 * Saves each LlmResult to the database.
 *
 * A configurable inter-request delay (default 2s) is applied between
 * findings to prevent GPU overload and system crashes on sustained runs.
 */
public class TriageStage extends AbstractPipelineStage {

    private static final Logger log = LoggerFactory.getLogger(TriageStage.class);

    /** Milliseconds to sleep between each LLM request. Prevents GPU overload on long runs. */
    private static final int DEFAULT_DELAY_MS = 2000;

    private final TriageStrategy       strategy;
    private final EvaluationRepository evalRepo;
    private final LlmResultRepository  llmRepo;
    private final String               runName;
    private final int                  delayMs;

    public TriageStage(List<PipelineObserver> observers,
                       TriageStrategy strategy,
                       EvaluationRepository evalRepo,
                       LlmResultRepository llmRepo,
                       String runName) {
        this(observers, strategy, evalRepo, llmRepo, runName, DEFAULT_DELAY_MS);
    }

    public TriageStage(List<PipelineObserver> observers,
                       TriageStrategy strategy,
                       EvaluationRepository evalRepo,
                       LlmResultRepository llmRepo,
                       String runName,
                       int delayMs) {
        super(observers);
        this.strategy = strategy;
        this.evalRepo = evalRepo;
        this.llmRepo  = llmRepo;
        this.runName  = runName;
        this.delayMs  = Math.max(0, delayMs);
    }

    @Override
    public String getName() { return "LLM Triage"; }

    @Override
    protected int progressAfter() { return 85; }

    @Override
    protected void doExecute(PipelineContext ctx) {
        List<Finding> sample = ctx.getSample();

        if (sample.isEmpty()) {
            log.warn("TriageStage: sample is empty — skipping LLM triage");
            return;
        }

        if (!strategy.isAvailable()) {
            ctx.fail("LLM backend (" + strategy.getModelName()
                + ") is not available. Is Ollama running?");
            return;
        }

        // Create evaluation run to group results
        EvaluationRun run = new EvaluationRun(runName,
            "LLM triage using " + strategy.getModelName()
            + " on " + sample.size() + " findings");
        evalRepo.save(run);

        // Save which findings are in this sample
        sample.forEach(f -> evalRepo.saveSampleFinding(run.getId(), f.getId()));

        log.info("TriageStage: processing {} findings with {}",
            sample.size(), strategy.getModelName());

        int processed = 0;
        for (Finding finding : sample) {
            try {
                TriageResult result = strategy.triage(finding);

                LlmResult llmResult = new LlmResult();
                llmResult.setFindingId      (finding.getId());
                llmResult.setEvaluationRunId(run.getId());
                llmResult.setLlmVerdict     (result.getVerdict());
                llmResult.setConfidence     (result.getConfidence());
                llmResult.setReasoning      (result.getReasoning());
                llmResult.setRemediation    (result.getRemediation());
                llmResult.setModelUsed      (strategy.getModelName());
                llmResult.setPromptVersion  (result.getPromptVersion());
                llmRepo.save(llmResult);

            } catch (Exception e) {
                log.warn("Failed to triage finding id={}: {}", finding.getId(), e.getMessage());
            }

            processed++;
            int progress = 50 + (int) ((double) processed / sample.size() * 35);
            emit(PipelineEvent.findingProcessed(progress));

            // Inter-request delay — gives GPU time to cool between inference calls
            if (delayMs > 0 && processed < sample.size()) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Triage interrupted during delay");
                    break;
                }
            }

            // Extra cooldown every 50 findings — longer pause to let VRAM settle
            if (processed % 50 == 0 && processed < sample.size()) {
                log.info("TriageStage: cooldown pause after {} findings", processed);
                try {
                    Thread.sleep(10_000); // 10 seconds every 50 findings
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("TriageStage complete: {}/{} findings processed", processed, sample.size());
        ctx.setEvaluationRun(run);
    }
}
