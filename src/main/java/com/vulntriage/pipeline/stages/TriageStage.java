package com.vulntriage.pipeline.stages;

import com.vulntriage.config.AppConfig;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
    /** When set, uses triageWithTemplate() instead of triage() so the stored prompt is applied. */
    private final String               promptTemplateStr;
    private final String               promptVersion;

    public TriageStage(List<PipelineObserver> observers,
                       TriageStrategy strategy,
                       EvaluationRepository evalRepo,
                       LlmResultRepository llmRepo,
                       String runName) {
        this(observers, strategy, evalRepo, llmRepo, runName, DEFAULT_DELAY_MS, null, null);
    }

    public TriageStage(List<PipelineObserver> observers,
                       TriageStrategy strategy,
                       EvaluationRepository evalRepo,
                       LlmResultRepository llmRepo,
                       String runName,
                       int delayMs) {
        this(observers, strategy, evalRepo, llmRepo, runName, delayMs, null, null);
    }

    public TriageStage(List<PipelineObserver> observers,
                       TriageStrategy strategy,
                       EvaluationRepository evalRepo,
                       LlmResultRepository llmRepo,
                       String runName,
                       int delayMs,
                       String promptTemplateStr,
                       String promptVersion) {
        super(observers);
        this.strategy          = strategy;
        this.evalRepo          = evalRepo;
        this.llmRepo           = llmRepo;
        this.runName           = runName;
        this.delayMs           = Math.max(0, delayMs);
        this.promptTemplateStr = promptTemplateStr;
        this.promptVersion     = promptVersion;
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
                + ") is not available. Check your provider settings.");
            return;
        }

        EvaluationRun run = new EvaluationRun(runName,
            "LLM triage using " + strategy.getModelName()
            + " on " + sample.size() + " findings");
        evalRepo.save(run);
        sample.forEach(f -> evalRepo.saveSampleFinding(run.getId(), f.getId()));

        int configuredThreads = AppConfig.getInstance().getTriageConcurrency();
        log.info("TriageStage: processing {} findings with {} (concurrency={})",
            sample.size(), strategy.getModelName(), configuredThreads);

        boolean useTemplate = promptTemplateStr != null && !promptTemplateStr.isBlank();
        com.vulntriage.app.AppContext appCtx = com.vulntriage.app.AppContext.getInstance();

        AtomicInteger processed   = new AtomicInteger(0);
        AtomicInteger concurrency = new AtomicInteger(configuredThreads);

        // Single writer thread keeps DB access serial and contention-free
        BlockingQueue<LlmResult> writeQueue = new LinkedBlockingQueue<>();
        Thread dbWriter = new Thread(() -> {
            while (true) {
                try {
                    LlmResult r = writeQueue.poll(2, TimeUnit.SECONDS);
                    if (r == null) {
                        if (Thread.currentThread().isInterrupted()) break;
                        continue;
                    }
                    // sentinel
                    if (r.getFindingId() == -1) break;
                    llmRepo.save(r);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "triage-db-writer");
        dbWriter.setDaemon(true);
        dbWriter.start();

        ExecutorService pool = Executors.newFixedThreadPool(configuredThreads,
            r -> { Thread t = new Thread(r, "triage-worker"); t.setDaemon(true); return t; });

        List<Future<?>> futures = new java.util.ArrayList<>(sample.size());

        for (Finding finding : sample) {
            if (appCtx.isStopRequested()) break;

            futures.add(pool.submit(() -> {
                if (appCtx.isStopRequested() || Thread.currentThread().isInterrupted()) return;

                try {
                    TriageResult result = useTemplate
                        ? strategy.triageWithTemplate(finding, promptTemplateStr, promptVersion, null)
                        : strategy.triage(finding);

                    LlmResult llmResult = new LlmResult();
                    llmResult.setFindingId      (finding.getId());
                    llmResult.setEvaluationRunId(run.getId());
                    llmResult.setLlmVerdict     (result.getVerdict());
                    llmResult.setConfidence     (result.getConfidence());
                    llmResult.setReasoning      (result.getReasoning());
                    llmResult.setRemediation    (result.getRemediation());
                    llmResult.setModelUsed      (strategy.getModelName());
                    llmResult.setPromptVersion  (result.getPromptVersion());
                    writeQueue.put(llmResult);

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // Auto-backoff: if a worker fails and concurrency > 1, drop by 1
                    int cur = concurrency.get();
                    if (cur > 1) {
                        int next = concurrency.decrementAndGet();
                        log.warn("Triage worker failed for finding id={} — backing off to {} thread(s): {}",
                            finding.getId(), next, e.getMessage());
                    } else {
                        log.warn("Failed to triage finding id={}: {}", finding.getId(), e.getMessage());
                    }
                }

                int done = processed.incrementAndGet();
                int progress = 50 + (int) ((double) done / sample.size() * 35);
                emit(PipelineEvent.findingProcessed(progress));

                // Inter-request delay only when running sequentially (concurrency=1)
                if (delayMs > 0 && concurrency.get() == 1 && done < sample.size()) {
                    try { Thread.sleep(delayMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }));
        }

        pool.shutdown();
        try {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }

        // Signal DB writer to finish and wait for it
        LlmResult sentinel = new LlmResult();
        sentinel.setFindingId(-1);
        writeQueue.offer(sentinel);
        try { dbWriter.join(10_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        log.info("TriageStage complete: {}/{} findings processed (final concurrency={})",
            processed.get(), sample.size(), concurrency.get());
        ctx.setEvaluationRun(run);
    }
}
