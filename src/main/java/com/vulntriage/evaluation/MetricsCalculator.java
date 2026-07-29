package com.vulntriage.evaluation;

import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.ManualReview;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.EvaluationRepository;
import com.vulntriage.repository.api.LlmResultRepository;
import com.vulntriage.repository.api.ManualReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Computes a full EvaluationReport by comparing LLM results
 * against manual reviews for a given evaluation run.
 *
 * Only findings that have BOTH a manual review AND an LLM result
 * contribute to the metrics. Unreviewed findings are excluded.
 */
public class MetricsCalculator {

    private static final Logger log = LoggerFactory.getLogger(MetricsCalculator.class);

    public EvaluationReport compute(long evaluationRunId,
                                    LlmResultRepository llmRepo,
                                    ManualReviewRepository reviewRepo) {
        return compute(evaluationRunId, llmRepo, reviewRepo, null, null);
    }

    public EvaluationReport compute(long evaluationRunId,
                                    LlmResultRepository llmRepo,
                                    ManualReviewRepository reviewRepo,
                                    EvaluationRepository evalRepo) {
        return compute(evaluationRunId, llmRepo, reviewRepo, evalRepo, null);
    }

    /**
     * Overload that accepts a set of valid finding IDs to restrict the computation
     * to findings that currently exist in the DB (skips orphaned review/LLM records).
     * Pass null to include all findings (original behaviour).
     */
    public EvaluationReport compute(long evaluationRunId,
                                    LlmResultRepository llmRepo,
                                    ManualReviewRepository reviewRepo,
                                    EvaluationRepository evalRepo,
                                    java.util.Set<Long> validFindingIds) {

        // Compute from manually-reviewed findings that also have any LLM result.
        // Filtered to validFindingIds when provided to skip orphaned rows.
        List<ManualReview> reviews = reviewRepo.findAll().stream()
            .filter(r -> validFindingIds == null || validFindingIds.contains(r.getFindingId()))
            .collect(Collectors.toList());
        List<LlmResult> llmResults = reviews.stream()
            .map(r -> llmRepo.findByFindingId(r.getFindingId()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList());

        // Index manual reviews by finding ID for O(1) lookup
        Map<Long, ManualReview> reviewMap = reviews.stream()
            .collect(Collectors.toMap(ManualReview::getFindingId, r -> r));

        ConfusionMatrix matrix = new ConfusionMatrix();
        int compared = 0;

        for (LlmResult llm : llmResults) {
            ManualReview manual = reviewMap.get(llm.getFindingId());
            if (manual == null) continue;  // not yet reviewed — skip

            matrix.increment(manual.getVerdict(), llm.getLlmVerdict());
            compared++;
        }

        log.info("MetricsCalculator: comparing {} finding pairs", compared);

        EvaluationReport report = new EvaluationReport();
        report.setEvaluationRunId(evaluationRunId);
        report.setTotalCompared  (compared);
        report.setConfusionMatrix(matrix);

        if (compared == 0) {
            log.warn("No finding pairs found — all metrics will be 0");
            return report;
        }

        // ── Accuracy ──────────────────────────────────────────────────────
        report.setAccuracy((double) matrix.diagonal() / compared);

        // ── TP Recall — did LLM catch real bugs? ──────────────────────────
        int manualTp = matrix.totalManual(Verdict.TP);
        int tpCorrect = matrix.get(Verdict.TP, Verdict.TP);
        report.setTpRecall(manualTp > 0 ? (double) tpCorrect / manualTp : 0.0);

        // ── False Negative Rate — real bugs dismissed as FP ───────────────
        int tpDismissed = matrix.get(Verdict.TP, Verdict.FP);
        report.setFalseNegativeRate(manualTp > 0 ? (double) tpDismissed / manualTp : 0.0);

        // ── TP Precision — of LLM's TP predictions, how many were right? ──
        int llmTp = matrix.totalLlm(Verdict.TP);
        report.setTpPrecision(llmTp > 0 ? (double) tpCorrect / llmTp : 0.0);

        // ── FP Agreement — did LLM correctly identify false positives? ────
        int manualFp = matrix.totalManual(Verdict.FP);
        int fpCorrect = matrix.get(Verdict.FP, Verdict.FP);
        report.setFpAgreement(manualFp > 0 ? (double) fpCorrect / manualFp : 0.0);

        // ── FP Rate — manual FPs wrongly escalated as TP by LLM ─────────
        int fpEscalated = matrix.get(Verdict.FP, Verdict.TP);
        report.setFalsePositiveRate(manualFp > 0 ? (double) fpEscalated / manualFp : 0.0);

        // ── REVIEW Agreement ──────────────────────────────────────────────
        int manualRev = matrix.totalManual(Verdict.REVIEW);
        int revCorrect = matrix.get(Verdict.REVIEW, Verdict.REVIEW);
        report.setReviewAgreement(manualRev > 0 ? (double) revCorrect / manualRev : 0.0);

        log.info("Metrics: accuracy={}% tpRecall={}% fnRate={}%",
            String.format("%.2f", report.getAccuracy()          * 100),
            String.format("%.2f", report.getTpRecall()          * 100),
            String.format("%.2f", report.getFalseNegativeRate() * 100));

        return report;
    }
}
