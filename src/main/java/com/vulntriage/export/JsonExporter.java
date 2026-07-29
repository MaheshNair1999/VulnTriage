package com.vulntriage.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vulntriage.evaluation.ConfusionMatrix;
import com.vulntriage.evaluation.EvaluationReport;
import com.vulntriage.domain.enums.Verdict;

import java.io.File;

/**
 * Exports an EvaluationReport to a structured JSON file.
 * The format matches the evaluation_report.json from the internship Python pipeline.
 */
public class JsonExporter {

    private final ObjectMapper mapper = new ObjectMapper();

    public void export(EvaluationReport report, String filePath) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        root.put("evaluation_run_id", report.getEvaluationRunId());
        root.put("total_compared",    report.getTotalCompared());

        // ── Metrics ────────────────────────────────────────────────────────
        ObjectNode metrics = root.putObject("metrics");
        metrics.put("overall_accuracy",      round(report.getAccuracy()));
        metrics.put("tp_recall",             round(report.getTpRecall()));
        metrics.put("false_negative_rate",   round(report.getFalseNegativeRate()));
        metrics.put("tp_precision",          round(report.getTpPrecision()));
        metrics.put("fp_agreement",          round(report.getFpAgreement()));
        metrics.put("false_positive_rate",   round(report.getFalsePositiveRate()));
        metrics.put("review_agreement",      round(report.getReviewAgreement()));

        // ── Confusion matrix ───────────────────────────────────────────────
        ConfusionMatrix matrix = report.getConfusionMatrix();
        if (matrix != null) {
            ObjectNode cm = root.putObject("confusion_matrix");

            // Rows: manual verdict; Columns: LLM verdict
            for (Verdict manual : Verdict.values()) {
                ObjectNode row = cm.putObject("manual_" + manual.name().toLowerCase());
                for (Verdict llm : Verdict.values()) {
                    row.put("llm_" + llm.name().toLowerCase(),
                        matrix.get(manual, llm));
                }
            }
        }

        // ── Notes ──────────────────────────────────────────────────────────
        root.put("note_on_confidence",
            "LLM confidence scores are self-reported by the model and are not "
            + "calibrated probabilities. They should be used as relative indicators only.");

        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(filePath), root);
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
