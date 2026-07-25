package com.vulntriage.pipeline.stages;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.evaluation.RiskScorer;
import com.vulntriage.event.PipelineObserver;
import com.vulntriage.pipeline.api.AbstractPipelineStage;
import com.vulntriage.pipeline.api.PipelineContext;
import com.vulntriage.repository.api.FindingRepository;
import com.vulntriage.repository.api.LlmResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pipeline stage that computes risk scores for findings and re-orders them.
 *
 * Reads LLM results from the database (if available) to use confidence
 * scores in the formula. Computes recurrence counts by checking how many
 * scan runs each fingerprint has appeared in.
 *
 * After this stage, ctx.getAllFindings() is replaced with a list sorted
 * by descending risk score — highest-priority findings come first.
 *
 * Placed in the pipeline after TRIAGE.
 *
 * Score formula:
 *   score = severityWeight * categoryWeight * (1 + 0.3 * normConfidence) * recurrenceFactor
 */
public class ScoreStage extends AbstractPipelineStage {

    private static final Logger log = LoggerFactory.getLogger(ScoreStage.class);

    private final RiskScorer          scorer;
    private final LlmResultRepository llmRepo;
    private final FindingRepository   findingRepo;

    public ScoreStage(List<PipelineObserver> observers,
                      LlmResultRepository llmRepo,
                      FindingRepository findingRepo) {
        super(observers);
        this.scorer      = new RiskScorer();
        this.llmRepo     = llmRepo;
        this.findingRepo = findingRepo;
    }

    @Override
    public String getName() { return "Score"; }

    @Override
    protected int progressAfter() { return 92; }

    @Override
    protected void doExecute(PipelineContext ctx) {
        List<Finding> findings = ctx.getAllFindings();
        if (findings.isEmpty()) {
            log.warn("ScoreStage: no findings to score");
            return;
        }

        // Load LLM results for the current evaluation run (if one exists)
        List<LlmResult> llmResults = Collections.emptyList();
        if (ctx.getEvaluationRun() != null) {
            llmResults = llmRepo.findByEvaluationRunId(ctx.getEvaluationRun().getId());
        }

        // Compute recurrence counts: how many scan runs each fingerprint appears in
        Map<String, Integer> recurrenceCounts = computeRecurrenceCounts(findings);

        // Score and sort
        List<RiskScorer.ScoredFinding> scored = scorer.score(findings, llmResults, recurrenceCounts);

        // Replace allFindings with sorted list
        List<Finding> sorted = scored.stream()
            .map(RiskScorer.ScoredFinding::getFinding)
            .collect(Collectors.toList());

        ctx.setAllFindings(sorted);

        double topScore = scored.isEmpty() ? 0 : scored.get(0).getScore();
        log.info("ScoreStage: {} findings scored and ranked (top score: {:.3f})",
            sorted.size(), topScore);
    }

    /**
     * Counts how many distinct scan runs each fingerprint has appeared in.
     * Findings that recur across runs are flagged as higher priority.
     */
    private Map<String, Integer> computeRecurrenceCounts(List<Finding> findings) {
        Map<String, Integer> counts = new HashMap<>();
        for (Finding f : findings) {
            if (f.getFingerprint() == null) continue;
            // Count how many findings share this fingerprint (each from a different scan run)
            long occurrences = findingRepo.findByRepositoryId(f.getRepositoryId())
                .stream()
                .filter(other -> f.getFingerprint().equals(other.getFingerprint()))
                .map(Finding::getScanRunId)
                .distinct()
                .count();
            counts.put(f.getFingerprint(), (int) occurrences);
        }
        return counts;
    }
}
