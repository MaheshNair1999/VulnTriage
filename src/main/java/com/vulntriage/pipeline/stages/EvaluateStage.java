package com.vulntriage.pipeline.stages;

import com.vulntriage.domain.EvaluationRun;
import com.vulntriage.event.PipelineObserver;
import com.vulntriage.evaluation.EvaluationReport;
import com.vulntriage.evaluation.MetricsCalculator;
import com.vulntriage.pipeline.api.AbstractPipelineStage;
import com.vulntriage.pipeline.api.PipelineContext;
import com.vulntriage.repository.api.EvaluationRepository;
import com.vulntriage.repository.api.LlmResultRepository;
import com.vulntriage.repository.api.ManualReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Stage 4 (final): Compute evaluation metrics.
 *
 * Requires manual reviews to already exist in the database.
 * Compares LLM verdicts against manual verdicts and produces
 * a full EvaluationReport (confusion matrix + all metrics).
 *
 * This stage does not fail the pipeline if no manual reviews exist
 * yet — it simply logs a warning. Manual review happens after scanning,
 * so evaluation is typically run as a separate pipeline pass.
 */
public class EvaluateStage extends AbstractPipelineStage {

    private static final Logger log = LoggerFactory.getLogger(EvaluateStage.class);

    private final EvaluationRepository  evalRepo;
    private final LlmResultRepository   llmRepo;
    private final ManualReviewRepository reviewRepo;
    private final MetricsCalculator      calculator;

    public EvaluateStage(List<PipelineObserver> observers,
                         EvaluationRepository evalRepo,
                         LlmResultRepository llmRepo,
                         ManualReviewRepository reviewRepo) {
        super(observers);
        this.evalRepo   = evalRepo;
        this.llmRepo    = llmRepo;
        this.reviewRepo = reviewRepo;
        this.calculator = new MetricsCalculator();
    }

    @Override
    public String getName() { return "Evaluate"; }

    @Override
    protected int progressAfter() { return 100; }

    @Override
    protected void doExecute(PipelineContext ctx) {
        EvaluationRun run = ctx.getEvaluationRun();

        if (run == null) {
            log.warn("EvaluateStage: no evaluation run in context — skipping");
            return;
        }

        long reviewCount = reviewRepo.countAll();
        if (reviewCount == 0) {
            log.warn("EvaluateStage: no manual reviews found — " +
                "complete manual review before running evaluation");
            return;
        }

        EvaluationReport report = calculator.compute(run.getId(), llmRepo, reviewRepo, evalRepo);
        log.info("Evaluation complete for run '{}': accuracy={}%, TP recall={}%",
            run.getName(),
            String.format("%.2f", report.getAccuracy()  * 100),
            String.format("%.2f", report.getTpRecall()  * 100));
    }
}
