package com.vulntriage.evaluation;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RiskScorerTest {

    private RiskScorer scorer;

    @BeforeEach
    void setup() { scorer = new RiskScorer(); }

    private Finding finding(Severity severity, String category, String fingerprint) {
        Finding f = new Finding();
        f.setId(1L);
        f.setSeverity   (severity);
        f.setCategory   (category);
        f.setFingerprint(fingerprint);
        return f;
    }

    private LlmResult llmResult(long findingId, int confidence) {
        LlmResult r = new LlmResult();
        r.setFindingId  (findingId);
        r.setLlmVerdict (Verdict.TP);
        r.setConfidence (confidence);
        return r;
    }

    // ── Score ordering ─────────────────────────────────────────────────────

    @Test
    void score_errorRanksHigherThanWarning_sameCategory() {
        Finding error   = finding(Severity.ERROR,   "xss", "fp-a");
        Finding warning = finding(Severity.WARNING, "xss", "fp-b");
        error.setId(1L); warning.setId(2L);

        var results = scorer.score(List.of(error, warning), List.of(), Map.of());
        assertEquals(error.getFingerprint(), results.get(0).getFinding().getFingerprint());
    }

    @Test
    void score_sqlInjectionRanksHigherThanXss_sameSeverity() {
        Finding sql = finding(Severity.WARNING, "sql_injection", "fp-sql");
        Finding xss = finding(Severity.WARNING, "xss",           "fp-xss");
        sql.setId(1L); xss.setId(2L);

        var results = scorer.score(List.of(sql, xss), List.of(), Map.of());
        assertEquals("fp-sql", results.get(0).getFinding().getFingerprint());
    }

    @Test
    void score_highConfidence_producesHigherScore_thanLowConfidence() {
        Finding f1 = finding(Severity.WARNING, "xss", "fp-1");
        Finding f2 = finding(Severity.WARNING, "xss", "fp-2");
        f1.setId(1L); f2.setId(2L);

        LlmResult high = llmResult(1L, 95);
        LlmResult low  = llmResult(2L, 20);

        var results = scorer.score(List.of(f1, f2), List.of(high, low), Map.of());
        assertEquals("fp-1", results.get(0).getFinding().getFingerprint());
    }

    @Test
    void score_recurrence_increasesScore() {
        Finding f = finding(Severity.WARNING, "xss", "fp-recur");
        f.setId(1L);

        double once  = scorer.computeScore(f, null, Map.of("fp-recur", 1));
        double twice = scorer.computeScore(f, null, Map.of("fp-recur", 3));
        assertTrue(twice > once, "Recurrent finding should score higher");
    }

    // ── Score formula ──────────────────────────────────────────────────────

    @Test
    void computeScore_errorSqlNoLlm_producesExpectedValue() {
        Finding f = finding(Severity.ERROR, "sql_injection", "fp-test");
        // severityWeight=1.0, categoryWeight=1.0, normConfidence=0.5(default), recurrence=1
        // score = 1.0 * 1.0 * (1 + 0.3 * 0.5) * 1.0 = 1.15
        double score = scorer.computeScore(f, null, Map.of());
        assertEquals(1.15, score, 0.001);
    }

    @Test
    void computeScore_infoUnknownCategory_producesLowestScore() {
        Finding f = finding(Severity.INFO, "unknown_category", "fp-low");
        double score = scorer.computeScore(f, null, Map.of());
        // severityWeight=0.2, categoryWeight=0.5(default), normConfidence=0.5, recurrence=1
        // score = 0.2 * 0.5 * 1.15 * 1.0 = 0.115
        assertEquals(0.115, score, 0.001);
    }

    @Test
    void score_emptyFindings_returnsEmptyList() {
        var results = scorer.score(List.of(), List.of(), Map.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void score_resultIsSortedDescending() {
        Finding high = finding(Severity.ERROR,   "sql_injection", "fp-high");
        Finding mid  = finding(Severity.WARNING, "xss",           "fp-mid");
        Finding low  = finding(Severity.INFO,    "configuration", "fp-low");
        high.setId(1L); mid.setId(2L); low.setId(3L);

        var results = scorer.score(List.of(low, high, mid), List.of(), Map.of());
        assertEquals(3, results.size());
        assertTrue(results.get(0).getScore() >= results.get(1).getScore());
        assertTrue(results.get(1).getScore() >= results.get(2).getScore());
    }
}
