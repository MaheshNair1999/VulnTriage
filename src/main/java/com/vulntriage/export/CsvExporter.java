package com.vulntriage.export;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.ManualReview;
import com.vulntriage.repository.api.FindingRepository;
import com.vulntriage.repository.api.LlmResultRepository;
import com.vulntriage.repository.api.ManualReviewRepository;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

/**
 * Exports triage results as a CSV file — one row per finding.
 *
 * Columns:
 *   finding_id, rule_id, file_path, line_number, severity, category,
 *   manual_verdict, llm_verdict, llm_confidence, llm_reasoning
 *
 * This format can be opened directly in Excel for further analysis.
 */
public class CsvExporter {

    public void export(long evaluationRunId,
                       LlmResultRepository llmRepo,
                       ManualReviewRepository reviewRepo,
                       FindingRepository findingRepo,
                       String filePath) throws Exception {

        // Use latest LLM result per finding (same approach as HTML report and MetricsCalculator)
        // rather than filtering by evaluation run ID, which would miss findings from earlier runs.
        List<ManualReview> reviews = reviewRepo.findAll();

        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath))) {
            // Header
            pw.println("finding_id,rule_id,file_path,line_number,severity,category,"
                + "manual_verdict,llm_verdict,llm_confidence,llm_reasoning");

            for (ManualReview review : reviews) {
                Optional<LlmResult> llmOpt = llmRepo.findByFindingId(review.getFindingId());
                if (llmOpt.isEmpty()) continue;
                LlmResult llm = llmOpt.get();

                Optional<Finding> findingOpt = findingRepo.findById(review.getFindingId());
                if (findingOpt.isEmpty()) continue;
                Finding f = findingOpt.get();

                pw.printf("%d,%s,%s,%s,%s,%s,%s,%s,%d,%s%n",
                    f.getId(),
                    csv(f.getRuleId()),
                    csv(f.getFilePath()),
                    f.getLineNumber() != null ? f.getLineNumber() : "",
                    f.getSeverity()   != null ? f.getSeverity().name() : "",
                    csv(f.getCategory()),
                    review.getVerdict().name(),
                    llm.getLlmVerdict().name(),
                    llm.getConfidence(),
                    csv(llm.getReasoning())
                );
            }
        }
    }

    /** Escape a value for CSV: wrap in quotes, escape internal quotes. */
    private String csv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"").replace("\n", " ").replace("\r", "");
        return "\"" + escaped + "\"";
    }
}
