package com.vulntriage.evaluation;

import java.util.ArrayList;

import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.ManualReview;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.LlmResultRepository;
import com.vulntriage.repository.api.ManualReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests MetricsCalculator using hand-crafted data that matches
 * the internship results (4 TP, 41 FP, 4 REVIEW, confusion matrix known).
 *
 * Uses simple in-memory stub implementations — no DB, no Mockito needed.
 */
class MetricsCalculatorTest {

    private MetricsCalculator calculator;

    // Known results from internship evaluation
    // Manual: 4 TP, 41 FP, 4 REVIEW
    // LLM:   TP=3/4 TP, 0 FP for manual TP, 1 REVIEW for manual TP
    //         13 TP / 15 FP / 13 REVIEW for manual FP
    //         4 TP / 0 FP / 0 REVIEW for manual REVIEW

    @BeforeEach
    void setup() {
        calculator = new MetricsCalculator();
    }

    /**
     * Build a dataset matching the internship confusion matrix:
     *                LLM:TP  LLM:FP  LLM:REVIEW
     * Manual:TP    [  3       0        1  ]
     * Manual:FP    [ 13      15       13  ]
     * Manual:REVIEW[  4       0        0  ]
     */
    private List<ManualReview> buildManualReviews() {
        List<ManualReview> reviews = new ArrayList<>();
        long id = 1;

        // 4 manual TP
        reviews.add(review(id++, Verdict.TP));
        reviews.add(review(id++, Verdict.TP));
        reviews.add(review(id++, Verdict.TP));
        reviews.add(review(id++, Verdict.TP));

        // 41 manual FP
        for (int i = 0; i < 41; i++) reviews.add(review(id++, Verdict.FP));

        // 4 manual REVIEW
        for (int i = 0; i < 4; i++) reviews.add(review(id++, Verdict.REVIEW));

        return reviews;
    }

    private List<LlmResult> buildLlmResults(List<ManualReview> manualReviews) {
        List<LlmResult> results = new ArrayList<>();
        int idx = 0;

        // Manual TP (4): LLM says 3 TP, 1 REVIEW
        results.add(llm(manualReviews.get(idx++).getFindingId(), Verdict.TP));
        results.add(llm(manualReviews.get(idx++).getFindingId(), Verdict.TP));
        results.add(llm(manualReviews.get(idx++).getFindingId(), Verdict.TP));
        results.add(llm(manualReviews.get(idx++).getFindingId(), Verdict.REVIEW));

        // Manual FP (41): 13 TP, 15 FP, 13 REVIEW
        for (int i = 0; i < 13; i++) results.add(llm(manualReviews.get(idx++).getFindingId(), Verdict.TP));
        for (int i = 0; i < 15; i++) results.add(llm(manualReviews.get(idx++).getFindingId(), Verdict.FP));
        for (int i = 0; i < 13; i++) results.add(llm(manualReviews.get(idx++).getFindingId(), Verdict.REVIEW));

        // Manual REVIEW (4): all predicted as TP
        for (int i = 0; i < 4; i++) results.add(llm(manualReviews.get(idx++).getFindingId(), Verdict.TP));

        return results;
    }

    // ── Stubs ──────────────────────────────────────────────────────────────

    private ManualReview review(long findingId, Verdict verdict) {
        ManualReview r = new ManualReview();
        r.setFindingId(findingId);
        r.setVerdict  (verdict);
        return r;
    }

    private LlmResult llm(long findingId, Verdict verdict) {
        LlmResult r = new LlmResult();
        r.setFindingId      (findingId);
        r.setEvaluationRunId(1L);
        r.setLlmVerdict     (verdict);
        r.setConfidence     (80);
        r.setModelUsed      ("test-model");
        r.setPromptVersion  ("v1");
        return r;
    }

    // Inline stub implementations (no Mockito, no DB)
    private LlmResultRepository llmRepo(List<LlmResult> results) {
        return new LlmResultRepository() {
            public void save(LlmResult r) {}
            public Optional<LlmResult> findByFindingIdAndRunId(long f, long r) { return Optional.empty(); }
            public List<LlmResult> findByEvaluationRunId(long id) { return results; }
            public long countByEvaluationRunId(long id) { return results.size(); }
        };
    }

    private ManualReviewRepository reviewRepo(List<ManualReview> reviews) {
        return new ManualReviewRepository() {
            public void saveOrUpdate(ManualReview r) {}
            public Optional<ManualReview> findByFindingId(long id) {
                return reviews.stream().filter(r -> r.getFindingId() == id).findFirst();
            }
            public List<ManualReview> findAll() { return reviews; }
            public List<ManualReview> findByVerdict(Verdict v) {
                return reviews.stream().filter(r -> r.getVerdict() == v).toList();
            }
            public long countByVerdict(Verdict v) {
                return reviews.stream().filter(r -> r.getVerdict() == v).count();
            }
            public long countAll() { return reviews.size(); }
        };
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void totalCompared_is49() {
        var manual  = buildManualReviews();
        var llm     = buildLlmResults(manual);
        var report  = calculator.compute(1L, llmRepo(llm), reviewRepo(manual));
        assertEquals(49, report.getTotalCompared());
    }

    @Test
    void tpRecall_is75percent() {
        var manual = buildManualReviews();
        var llm    = buildLlmResults(manual);
        var report = calculator.compute(1L, llmRepo(llm), reviewRepo(manual));
        assertEquals(0.75, report.getTpRecall(), 0.001);
    }

    @Test
    void falseNegativeRate_is0() {
        var manual = buildManualReviews();
        var llm    = buildLlmResults(manual);
        var report = calculator.compute(1L, llmRepo(llm), reviewRepo(manual));
        assertEquals(0.0, report.getFalseNegativeRate(), 0.001);
    }

    @Test
    void accuracy_is3673percent() {
        var manual = buildManualReviews();
        var llm    = buildLlmResults(manual);
        var report = calculator.compute(1L, llmRepo(llm), reviewRepo(manual));
        // diagonal = 3 (TP/TP) + 15 (FP/FP) + 0 (REV/REV) = 18
        // accuracy = 18/49 = 36.73%
        assertEquals(18.0 / 49.0, report.getAccuracy(), 0.001);
    }

    @Test
    void tpPrecision_is15percent() {
        var manual = buildManualReviews();
        var llm    = buildLlmResults(manual);
        var report = calculator.compute(1L, llmRepo(llm), reviewRepo(manual));
        // LLM TP = 3+13+4 = 20, correct = 3
        assertEquals(3.0 / 20.0, report.getTpPrecision(), 0.001);
    }

    @Test
    void fpAgreement_is3659percent() {
        var manual = buildManualReviews();
        var llm    = buildLlmResults(manual);
        var report = calculator.compute(1L, llmRepo(llm), reviewRepo(manual));
        // Manual FP = 41, LLM correctly said FP = 15
        assertEquals(15.0 / 41.0, report.getFpAgreement(), 0.001);
    }

    @Test
    void confusionMatrix_diagonalMatchesExpected() {
        var manual = buildManualReviews();
        var llm    = buildLlmResults(manual);
        var report = calculator.compute(1L, llmRepo(llm), reviewRepo(manual));
        var matrix = report.getConfusionMatrix();

        assertEquals(3,  matrix.get(Verdict.TP,     Verdict.TP));
        assertEquals(0,  matrix.get(Verdict.TP,     Verdict.FP));
        assertEquals(1,  matrix.get(Verdict.TP,     Verdict.REVIEW));
        assertEquals(13, matrix.get(Verdict.FP,     Verdict.TP));
        assertEquals(15, matrix.get(Verdict.FP,     Verdict.FP));
        assertEquals(13, matrix.get(Verdict.FP,     Verdict.REVIEW));
        assertEquals(4,  matrix.get(Verdict.REVIEW, Verdict.TP));
    }

    @Test
    void emptyData_returnsZeroMetrics() {
        var report = calculator.compute(1L, llmRepo(List.of()), reviewRepo(List.of()));
        assertEquals(0, report.getTotalCompared());
        assertEquals(0.0, report.getAccuracy(), 0.001);
        assertEquals(0.0, report.getTpRecall(), 0.001);
    }
}
