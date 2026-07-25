package com.vulntriage.evaluation;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.scoring.FormulaEngine;
import com.vulntriage.scoring.FormulaNode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes a risk score for each finding using a user-configurable formula string.
 *
 * The formula is parsed at construction time into an AST by FormulaEngine.
 * At scoring time the AST is evaluated with four variable bindings per finding:
 *
 *   severity         — ERROR=1.0, WARNING=0.6, INFO=0.2
 *   category_weight  — sql_injection=1.0, command_injection=1.0, xss=0.8,
 *                      path_traversal=0.8, ssrf=0.8, deserialization=0.9,
 *                      hardcoded_secret=0.7, csrf=0.6, template_injection=0.6,
 *                      xxe=0.6, others=0.5
 *   confidence       — LLM confidence (0-100) normalised to 0-1;
 *                      defaults to 0.5 when no LLM result exists
 *   recurrence       — 1.0 + 0.1 * (scan-run appearances - 1)
 *
 * Default formula (matches original hardcoded behaviour):
 *   "severity * category_weight * (1 + 0.3 * confidence) * recurrence"
 */
public class RiskScorer {

    public static final String DEFAULT_FORMULA =
        "severity * category_weight * (1 + 0.3 * confidence) * recurrence";

    // ── Severity weights ───────────────────────────────────────────────────
    private static final Map<Severity, Double> SEVERITY_WEIGHTS = Map.of(
        Severity.ERROR,   1.0,
        Severity.WARNING, 0.6,
        Severity.INFO,    0.2
    );

    // ── Category weights ───────────────────────────────────────────────────
    private static final Map<String, Double> CATEGORY_WEIGHTS = Map.ofEntries(
        Map.entry("sql_injection",      1.0),
        Map.entry("command_injection",  1.0),
        Map.entry("deserialization",    0.9),
        Map.entry("xss",                0.8),
        Map.entry("path_traversal",     0.8),
        Map.entry("ssrf",               0.8),
        Map.entry("hardcoded_secret",   0.7),
        Map.entry("csrf",               0.6),
        Map.entry("template_injection", 0.6),
        Map.entry("xxe",                0.6)
    );

    private static final double DEFAULT_CATEGORY_WEIGHT = 0.5;
    private static final double DEFAULT_CONFIDENCE      = 0.5;

    private final FormulaEngine engine;
    private final FormulaNode   formulaTree;
    private final String        formulaString;

    /** Uses the default formula — preserves existing behaviour. */
    public RiskScorer() {
        this(DEFAULT_FORMULA);
    }

    /** Uses a custom formula string, parsed immediately into an AST. */
    public RiskScorer(String formula) {
        this.engine        = new FormulaEngine();
        this.formulaString = formula;
        this.formulaTree   = engine.compile(formula);
    }

    /** Returns the formula string this scorer was built with. */
    public String getFormula() { return formulaString; }

    /**
     * Score a list of findings and return them sorted by risk (highest first).
     */
    public List<ScoredFinding> score(List<Finding> findings,
                                      List<LlmResult> llmResults,
                                      Map<String, Integer> recurrenceCounts) {

        Map<Long, LlmResult> llmMap = llmResults.stream()
            .collect(Collectors.toMap(LlmResult::getFindingId, r -> r, (a, b) -> a));

        return findings.stream()
            .map(f -> new ScoredFinding(f, computeScore(f, llmMap.get(f.getId()), recurrenceCounts)))
            .sorted(Comparator.comparingDouble(ScoredFinding::getScore).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Compute the risk score for a single finding by evaluating the formula AST.
     * Package-private for unit testing.
     */
    double computeScore(Finding finding,
                        LlmResult llmResult,
                        Map<String, Integer> recurrenceCounts) {

        double severityWeight = SEVERITY_WEIGHTS.getOrDefault(finding.getSeverity(), 0.2);

        double categoryWeight = CATEGORY_WEIGHTS.getOrDefault(
            finding.getCategory() != null ? finding.getCategory().toLowerCase() : "",
            DEFAULT_CATEGORY_WEIGHT);

        double normConfidence = (llmResult != null)
            ? llmResult.getConfidence() / 100.0
            : DEFAULT_CONFIDENCE;

        int recurrences = recurrenceCounts.getOrDefault(finding.getFingerprint(), 1);
        double recurrenceFactor = 1.0 + 0.1 * (recurrences - 1);

        Map<String, Double> variables = Map.of(
            "severity",        severityWeight,
            "category_weight", categoryWeight,
            "confidence",      normConfidence,
            "recurrence",      recurrenceFactor
        );

        return engine.evaluate(formulaTree, variables);
    }

    // ── Scored finding wrapper ─────────────────────────────────────────────

    public static class ScoredFinding {
        private final Finding finding;
        private final double  score;

        public ScoredFinding(Finding finding, double score) {
            this.finding = finding;
            this.score   = score;
        }

        public Finding getFinding() { return finding; }
        public double  getScore()   { return score; }

        public String getFormattedScore() {
            return String.format("%.3f", score);
        }
    }
}
