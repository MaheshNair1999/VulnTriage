package com.vulntriage.export;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.ManualReview;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.evaluation.ConfusionMatrix;
import com.vulntriage.evaluation.EvaluationReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HtmlReportExporter {

    public void export(
            String runName,
            EvaluationReport report,
            List<Finding> findings,
            Map<Long, ManualReview> reviewsByFindingId,
            Map<Long, LlmResult>   llmByFindingId,
            List<LlmResult>        allLlmResults,
            String outputPath) throws IOException {

        String html = buildHtml(runName, report, findings, reviewsByFindingId, llmByFindingId, allLlmResults);
        Files.writeString(Path.of(outputPath), html, StandardCharsets.UTF_8);
    }

    private record VerMetrics(int tpTp, int tpTotal, int fpFp, int fpTotal, int predTp, int total) {
        double tpRecall()    { return tpTotal > 0 ? (double) tpTp / tpTotal : 0; }
        double fpAgreement() { return fpTotal > 0 ? (double) fpFp / fpTotal : 0; }
        double tpPrecision() { return predTp  > 0 ? (double) tpTp / predTp  : 0; }
    }

    private VerMetrics computeVerMetrics(List<LlmResult> results, Map<Long, ManualReview> reviewMap) {
        Map<Long, LlmResult> deduped = new java.util.LinkedHashMap<>();
        for (LlmResult lr : results)
            deduped.merge(lr.getFindingId(), lr, (a, b) -> a.getId() > b.getId() ? a : b);
        int tpTp = 0, tpTotal = 0, fpFp = 0, fpTotal = 0, predTp = 0;
        for (LlmResult lr : deduped.values()) {
            ManualReview mr = reviewMap.get(lr.getFindingId());
            if (mr == null) continue;
            if (mr.getVerdict() == Verdict.TP) { tpTotal++; if (lr.getLlmVerdict() == Verdict.TP) tpTp++; }
            if (mr.getVerdict() == Verdict.FP) { fpTotal++; if (lr.getLlmVerdict() == Verdict.FP) fpFp++; }
            if (lr.getLlmVerdict() == Verdict.TP) predTp++;
        }
        return new VerMetrics(tpTp, tpTotal, fpFp, fpTotal, predTp, deduped.size());
    }

    private String buildHtml(
            String runName,
            EvaluationReport report,
            List<Finding> findings,
            Map<Long, ManualReview> reviewsByFindingId,
            Map<Long, LlmResult>   llmByFindingId,
            List<LlmResult>        allLlmResults) {

        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"));

        StringBuilder sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>VulnTriage Evaluation Report</title>
            <style>
            *,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
            :root{
              --navy:#1B2E4B;--blue:#1D4ED8;--slate:#475569;--muted:#94A3B8;
              --border:#E2E8F0;--bg:#F8FAFC;--card:#FFFFFF;--text:#111827;
              --green:#059669;--red:#DC2626;--amber:#D97706;--purple:#7C3AED;
              --tp-bg:#D1FAE5;--tp-fg:#065F46;
              --fp-bg:#FEE2E2;--fp-fg:#991B1B;
              --rv-bg:#FEF3C7;--rv-fg:#92400E;
            }
            body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;
              background:var(--bg);color:var(--slate);line-height:1.6;font-size:14px}
            .page{max-width:1100px;margin:0 auto;padding:0 32px 80px}

            /* Cover */
            .cover{padding:48px 0 36px;border-bottom:3px solid var(--navy);margin-bottom:40px}
            .cover-eye{font-size:10px;font-weight:700;letter-spacing:.14em;text-transform:uppercase;
              color:var(--blue);margin-bottom:12px}
            .cover h1{font-family:Georgia,serif;font-size:2.4rem;font-weight:700;
              color:var(--navy);line-height:1.1;margin-bottom:8px}
            .cover-meta{display:flex;gap:32px;flex-wrap:wrap;margin-top:16px}
            .cover-meta-item label{display:block;font-size:10px;font-weight:700;
              letter-spacing:.12em;text-transform:uppercase;color:var(--muted);margin-bottom:2px}
            .cover-meta-item span{font-size:13px;color:var(--navy);font-weight:600}

            /* Sections */
            .section{margin-bottom:44px}
            .section-title{font-family:Georgia,serif;font-size:1.35rem;font-weight:700;
              color:var(--navy);border-bottom:2px solid var(--border);padding-bottom:8px;margin-bottom:18px}

            /* Metric cards */
            .metric-row{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:14px}
            .metric-card{background:var(--card);border:1px solid var(--border);border-radius:10px;
              padding:16px 18px}
            .metric-bar{height:4px;width:32px;border-radius:2px;margin-bottom:10px}
            .metric-val{font-size:2rem;font-weight:700;font-family:'Courier New',monospace;line-height:1.1}
            .metric-name{font-size:10px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;
              color:var(--muted);margin-top:4px}
            .metric-desc{font-size:11px;color:#9CA3AF;margin-top:3px}

            /* Confusion matrix */
            .matrix-wrap{overflow-x:auto}
            .matrix{border-collapse:collapse;font-size:13px}
            .matrix th{background:#F1F5F9;color:var(--navy);padding:9px 20px;text-align:center;
              font-size:10.5px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;
              border:1px solid var(--border)}
            .matrix td{border:1px solid var(--border);padding:14px 20px;text-align:center;
              font-family:'Courier New',monospace;font-size:1.4rem;font-weight:700}
            .matrix .diag-tp{background:var(--tp-bg);color:var(--green)}
            .matrix .diag-fp{background:var(--fp-bg);color:var(--red)}
            .matrix .diag-rv{background:var(--rv-bg);color:var(--amber)}
            .matrix .off{background:#F9FAFB;color:var(--slate)}
            .matrix .total{background:#F1F5F9;color:var(--navy);font-size:1rem}

            /* Scanner breakdown */
            .sc-hdr{display:flex;justify-content:space-between;align-items:center;
              border-bottom:2px solid var(--border);padding-bottom:8px;margin-bottom:18px}
            .sc-title{border:none;padding:0;margin:0}
            .sc-select{font-size:12px;padding:5px 10px;border:1px solid var(--border);
              border-radius:6px;background:var(--card);color:var(--navy);font-weight:600;cursor:pointer}
            .sc-4col{grid-template-columns:repeat(4,1fr)!important;margin-bottom:0}
            .sc-verdict-row{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:16px}
            .sc-vbox{background:var(--card);border:1px solid var(--border);border-radius:10px;padding:18px 20px}
            .sc-vbox-title{font-weight:700;font-size:13px;color:var(--navy);margin-bottom:12px}
            .sc-vrow{display:flex;justify-content:space-between;padding:7px 0;
              border-bottom:1px solid var(--border);font-size:13px;color:var(--slate)}
            .sc-vrow:last-child{border-bottom:none}
            .sc-vnum{font-weight:600;color:var(--navy)}
            .sc-card{background:var(--card);border:1px solid var(--border);border-radius:8px;
              padding:14px 16px}
            .sc-val{font-size:1.6rem;font-weight:700;font-family:'Courier New',monospace}
            .sc-name{font-size:10px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;
              color:var(--muted);margin-top:4px}

            /* Findings table */
            .overflow-x{overflow-x:auto}
            .findings-table{width:100%;border-collapse:collapse;font-size:12.5px}
            .findings-table th{background:var(--navy);color:#fff;padding:8px 12px;text-align:left;
              font-size:10px;letter-spacing:.07em;text-transform:uppercase;white-space:nowrap}
            .findings-table td{padding:7px 12px;border-bottom:1px solid var(--border);
              vertical-align:top}
            .findings-table tr:nth-child(even) td{background:#F8FAFC}
            .findings-table tr:hover td{background:#EFF6FF}
            .findings-table td.mono{font-family:'Courier New',monospace;font-size:11.5px}
            .findings-table td.num{color:var(--muted);font-size:11px;font-family:'Courier New',monospace}
            .findings-table td.file-cell{max-width:160px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .findings-table td.mismatch{background:#FFF7ED!important}

            /* Badges */
            .badge{display:inline-block;padding:2px 8px;border-radius:12px;
              font-size:10.5px;font-weight:700;font-family:'Courier New',monospace}
            .badge-tp{background:var(--tp-bg);color:var(--tp-fg)}
            .badge-fp{background:var(--fp-bg);color:var(--fp-fg)}
            .badge-rv{background:var(--rv-bg);color:var(--rv-fg)}
            .badge-none{background:#F1F5F9;color:var(--muted)}
            .badge-sev-error{background:#FEE2E2;color:#991B1B}
            .badge-sev-warning{background:#FEF3C7;color:#92400E}
            .badge-sev-info{background:#EFF6FF;color:#1E40AF}
            .match-ok{color:var(--green);font-weight:700;font-size:13px}
            .match-no{color:var(--red);font-weight:700;font-size:13px}
            .match-na{color:var(--muted);font-size:13px}
            .conf{font-size:11px;color:var(--muted)}

            @page{margin:0}
            @media print{
              body{font-size:12px}
              .page{padding:1.4cm 1.8cm 1.2cm}
              .cover{padding:28px 0 22px}
              .section{margin-bottom:28px}
              .findings-table th,.findings-table td{padding:5px 8px}
              .findings-table tr:hover td{background:inherit}
            }
            </style>
            </head>
            <body>
            <div class="page">
            """);

        // ── Cover ──────────────────────────────────────────────────────────
        sb.append("<header class=\"cover\">");
        sb.append("<div class=\"cover-eye\">VulnTriage &mdash; Evaluation Report</div>");
        sb.append("<h1>").append(esc(runName)).append("</h1>");
        sb.append("<div class=\"cover-meta\">");
        sb.append(metaItem("Generated", date));
        sb.append(metaItem("Total Pairs Compared", String.valueOf(report.getTotalCompared())));
        sb.append(metaItem("Findings in Database", String.valueOf(findings.size())));
        sb.append("</div></header>\n\n");

        // ── Metrics ────────────────────────────────────────────────────────
        sb.append("<section class=\"section\">");
        sb.append("<h2 class=\"section-title\">Key Metrics</h2>");
        sb.append("<div class=\"metric-row\">");
        sb.append(metricCard("TP Recall",          pct(report.getTpRecall()),          "#059669", "LLM caught this fraction of real vulnerabilities"));
        sb.append(metricCard("False Negative Rate", pct(report.getFalseNegativeRate()), report.getFalseNegativeRate() == 0 ? "#059669" : "#DC2626", "Real bugs the LLM dismissed as safe &mdash; lower is better"));
        sb.append(metricCard("Overall Accuracy",    pct(report.getAccuracy()),          "#1D4ED8", "Agreement across all three verdict classes"));
        sb.append("</div><div class=\"metric-row\" style=\"grid-template-columns:repeat(4,1fr)\">");
        sb.append(metricCard("TP Precision",       pct(report.getTpPrecision()),       "#D97706", "Of LLM&rsquo;s TP predictions, how many were correct"));
        sb.append(metricCard("FP Agreement",       pct(report.getFpAgreement()),       "#D97706", "LLM correctly identified false positives"));
        sb.append(metricCard("FP Rate",            pct(report.getFalsePositiveRate()), report.getFalsePositiveRate() == 0 ? "#059669" : "#DC2626", "Noise the LLM wrongly escalated as real bugs &mdash; lower is better"));
        sb.append(metricCard("REVIEW Agreement",   pct(report.getReviewAgreement()),   "#7C3AED", "LLM used REVIEW on findings the reviewer found uncertain"));
        sb.append("</div></section>\n\n");

        // ── Scanner Summary ────────────────────────────────────────────────
        long totalReviewed = reviewsByFindingId.size();
        long totalLlm      = llmByFindingId.size();

        // Build per-scanner stats — only scanners that actually produced findings
        Map<ScannerType, List<Finding>> findingsByScanner = findings.stream()
            .filter(f -> f.getSource() != null)
            .collect(Collectors.groupingBy(Finding::getSource));

        List<String> scannerJsonList = findingsByScanner.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .map(entry -> {
                ScannerType   type = entry.getKey();
                List<Finding> sf   = entry.getValue();

                java.util.Set<Long> sfIds = sf.stream()
                    .map(Finding::getId).collect(Collectors.toSet());

                List<Finding> sfRev = sf.stream()
                    .filter(f -> reviewsByFindingId.containsKey(f.getId()))
                    .collect(Collectors.toList());
                List<Finding> sfLlm = sf.stream()
                    .filter(f -> llmByFindingId.containsKey(f.getId()))
                    .collect(Collectors.toList());

                long agreed = sfRev.stream().filter(f -> {
                    LlmResult l = llmByFindingId.get(f.getId());
                    return l != null && l.getLlmVerdict() == reviewsByFindingId.get(f.getId()).getVerdict();
                }).count();

                String aPct = sfRev.isEmpty() ? "—"
                    : String.format("%.1f%%", (double) agreed / sfRev.size() * 100);

                long mTP  = sfRev.stream().filter(f -> reviewsByFindingId.get(f.getId()).getVerdict() == Verdict.TP).count();
                long mFP  = sfRev.stream().filter(f -> reviewsByFindingId.get(f.getId()).getVerdict() == Verdict.FP).count();
                long mRev = sfRev.stream().filter(f -> reviewsByFindingId.get(f.getId()).getVerdict() == Verdict.REVIEW).count();

                long lTP  = sfLlm.stream().filter(f -> llmByFindingId.get(f.getId()).getLlmVerdict() == Verdict.TP).count();
                long lFP  = sfLlm.stream().filter(f -> llmByFindingId.get(f.getId()).getLlmVerdict() == Verdict.FP).count();
                long lRev = sfLlm.stream().filter(f -> llmByFindingId.get(f.getId()).getLlmVerdict() == Verdict.REVIEW).count();

                // Per-version LLM triaged counts (across all runs, deduplicated per finding)
                java.util.Map<String, java.util.Set<Long>> versionIds = new java.util.LinkedHashMap<>();
                for (LlmResult lr : allLlmResults) {
                    if (!sfIds.contains(lr.getFindingId())) continue;
                    String v = lr.getPromptVersion() != null ? lr.getPromptVersion() : "unknown";
                    versionIds.computeIfAbsent(v, k -> new java.util.HashSet<>()).add(lr.getFindingId());
                }
                String vCountsJson = "[" + versionIds.entrySet().stream()
                    .map(e -> "{\"v\":\"" + e.getKey() + "\",\"n\":" + e.getValue().size() + "}")
                    .collect(Collectors.joining(",")) + "]";

                return String.format(
                    "{\"name\":\"%s\",\"color\":\"%s\",\"total\":%d,\"reviewed\":%d,"
                    + "\"agreePct\":\"%s\",\"versionCounts\":%s,"
                    + "\"mTP\":%d,\"mFP\":%d,\"mREV\":%d,"
                    + "\"lTP\":%d,\"lFP\":%d,\"lREV\":%d}",
                    type.name(), scannerColor(type), sf.size(),
                    sfRev.size(), aPct, vCountsJson,
                    mTP, mFP, mRev, lTP, lFP, lRev);
            })
            .collect(Collectors.toList());

        String scannerJson = "[" + String.join(",", scannerJsonList) + "]";

        sb.append("<section class=\"section\">");
        sb.append("<div class=\"sc-hdr\">");
        sb.append("<h2 class=\"section-title sc-title\">Scanner Breakdown</h2>");
        if (!findingsByScanner.isEmpty()) {
            sb.append("<select class=\"sc-select\" id=\"sc-sel\" onchange=\"scSwitch(this.value)\"></select>");
        }
        sb.append("</div>");
        sb.append("<div id=\"sc-cards\" style=\"display:grid;gap:14px;margin-bottom:0\"></div>");
        sb.append("<div class=\"sc-verdict-row\" id=\"sc-verdicts\"></div>");
        sb.append("<script>\n");
        sb.append("var SC_DATA=").append(scannerJson).append(";\n");
        sb.append("function scSwitch(n){\n");
        sb.append("  var d=SC_DATA.find(function(s){return s.name===n;});\n");
        sb.append("  if(!d)return;\n");
        sb.append("  function pct(v,t){return t>0?' ('+Math.round(v/t*100)+'%)':''}\n");
        sb.append("  var cols=2+d.versionCounts.length+1;\n");
        sb.append("  document.getElementById('sc-cards').style.gridTemplateColumns='repeat('+cols+',1fr)';\n");
        sb.append("  var vCards='';\n");
        sb.append("  d.versionCounts.forEach(function(vc){vCards+=scCard(vc.n,'LLM Triaged ('+vc.v+')','Findings processed by '+vc.v,'#D97706');});\n");
        sb.append("  document.getElementById('sc-cards').innerHTML=\n");
        sb.append("    scCard(d.total,'Total Findings','From this scanner in database',d.color)+\n");
        sb.append("    scCard(d.reviewed,'Manually Reviewed','Findings with TP/FP/REVIEW verdict','#059669')+\n");
        sb.append("    vCards+\n");
        sb.append("    scCard(d.agreePct,'LLM Agreement','LLM matched manual verdict','#7C3AED');\n");
        sb.append("  document.getElementById('sc-verdicts').innerHTML=\n");
        sb.append("    '<div class=\"sc-vbox\"><div class=\"sc-vbox-title\">Manual Review Verdicts</div>'+\n");
        sb.append("    scRow('True Positive (TP)',d.mTP,pct(d.mTP,d.reviewed))+\n");
        sb.append("    scRow('False Positive (FP)',d.mFP,pct(d.mFP,d.reviewed))+\n");
        sb.append("    scRow('Needs Review',d.mREV,pct(d.mREV,d.reviewed))+\n");
        sb.append("    '</div><div class=\"sc-vbox\"><div class=\"sc-vbox-title\">LLM Triage Verdicts</div>'+\n");
        sb.append("    scRow('True Positive (TP)',d.lTP,pct(d.lTP,d.llmTriaged))+\n");
        sb.append("    scRow('False Positive (FP)',d.lFP,pct(d.lFP,d.llmTriaged))+\n");
        sb.append("    scRow('Needs Review',d.lREV,pct(d.lREV,d.llmTriaged))+\n");
        sb.append("    '</div>';\n");
        sb.append("}\n");
        sb.append("function scCard(v,n,desc,c){\n");
        sb.append("  return '<div class=\"metric-card\">'\n");
        sb.append("    +'<div class=\"metric-bar\" style=\"background:'+c+'\"></div>'\n");
        sb.append("    +'<div class=\"metric-val\" style=\"color:'+c+'\">'+v+'</div>'\n");
        sb.append("    +'<div class=\"metric-name\">'+n+'</div>'\n");
        sb.append("    +'<div class=\"metric-desc\">'+desc+'</div></div>';\n");
        sb.append("}\n");
        sb.append("function scRow(lbl,cnt,p){\n");
        sb.append("  return '<div class=\"sc-vrow\"><span>'+lbl+'</span><span class=\"sc-vnum\">'+cnt+p+'</span></div>';\n");
        sb.append("}\n");
        sb.append("var sel=document.getElementById('sc-sel');\n");
        sb.append("if(sel){\n");
        sb.append("  SC_DATA.forEach(function(s){var o=document.createElement('option');o.value=s.name;o.textContent=s.name;sel.appendChild(o);});\n");
        sb.append("  if(SC_DATA.length>0)scSwitch(SC_DATA[0].name);\n");
        sb.append("}\n");
        sb.append("</script>\n");
        sb.append("</section>\n\n");

        // ── Version-Based Evaluation ───────────────────────────────────────
        if (!allLlmResults.isEmpty()) {
            // Group by prompt version, preserving insertion order
            java.util.Map<String, List<LlmResult>> byVersion = new java.util.LinkedHashMap<>();
            for (LlmResult lr : allLlmResults) {
                String v = lr.getPromptVersion() != null ? lr.getPromptVersion() : "unknown";
                byVersion.computeIfAbsent(v, k -> new java.util.ArrayList<>()).add(lr);
            }

            sb.append("<section class=\"section\">");
            sb.append("<h2 class=\"section-title\">Version-Based Evaluation</h2>");
            sb.append("<p style=\"font-size:12px;color:var(--muted);margin-bottom:18px\">")
              .append("Per-prompt-version metrics across all findings triaged by each version</p>");

            for (java.util.Map.Entry<String, List<LlmResult>> entry : byVersion.entrySet()) {
                VerMetrics vm = computeVerMetrics(entry.getValue(), reviewsByFindingId);
                ConfusionMatrix cm = computeVerMatrix(entry.getValue(), reviewsByFindingId);
                sb.append("<div style=\"margin-bottom:32px\">");
                sb.append("<div style=\"font-size:13px;font-weight:700;color:var(--blue);margin-bottom:10px\">")
                  .append(esc(entry.getKey()))
                  .append(" &nbsp;<span style=\"font-weight:400;color:var(--muted);font-size:12px\">(")
                  .append(vm.total()).append(" findings triaged)</span></div>");
                sb.append("<div class=\"metric-row\">");
                sb.append(metricCard("TP Recall",    pct(vm.tpRecall()),    "#059669",
                    vm.tpTp() + " / " + vm.tpTotal() + " real vulnerabilities caught"));
                sb.append(metricCard("FP Agreement", pct(vm.fpAgreement()), "#D97706",
                    vm.fpFp() + " / " + vm.fpTotal() + " false positives agreed"));
                sb.append(metricCard("TP Precision", pct(vm.tpPrecision()), "#D97706",
                    vm.tpTp() + " / " + vm.predTp() + " TP predictions correct"));
                sb.append("</div>");
                sb.append(matrixHtml(cm));
                sb.append("</div>");
            }
            sb.append("</section>\n\n");

            // ── Total Evaluation ───────────────────────────────────────────
            VerMetrics total = computeVerMetrics(allLlmResults, reviewsByFindingId);
            sb.append("<section class=\"section\">");
            sb.append("<h2 class=\"section-title\">Total Evaluation</h2>");
            sb.append("<p style=\"font-size:12px;color:var(--muted);margin-bottom:14px\">")
              .append("Aggregate metrics across all LLM results &mdash; all versions combined, ")
              .append("latest result per finding</p>");
            sb.append("<div class=\"metric-row\" style=\"grid-template-columns:repeat(4,1fr)\">");
            sb.append(metricCard("TP Recall",        pct(total.tpRecall()),    "#059669",
                total.tpTp() + " / " + total.tpTotal() + " real vulnerabilities caught"));
            sb.append(metricCard("FP Agreement",     pct(total.fpAgreement()), "#D97706",
                total.fpFp() + " / " + total.fpTotal() + " false positives agreed"));
            sb.append(metricCard("TP Precision",     pct(total.tpPrecision()), "#D97706",
                total.tpTp() + " / " + total.predTp() + " TP predictions correct"));
            sb.append(metricCard("Findings Triaged", String.valueOf(total.total()), "#1D4ED8",
                "Unique findings with LLM result and manual review"));
            sb.append("</div></section>\n\n");
        }

        // ── Findings Table ─────────────────────────────────────────────────
        // Build per-finding, per-version lookup (keep most-recent result per finding+version)
        java.util.Map<Long, java.util.Map<String, LlmResult>> verByFinding = new java.util.LinkedHashMap<>();
        for (LlmResult lr : allLlmResults) {
            String ver = lr.getPromptVersion() != null ? lr.getPromptVersion() : "unknown";
            verByFinding
                .computeIfAbsent(lr.getFindingId(), k -> new java.util.LinkedHashMap<>())
                .merge(ver, lr, (a, b) -> a.getId() > b.getId() ? a : b);
        }
        java.util.List<String> versions = allLlmResults.stream()
            .map(lr -> lr.getPromptVersion() != null ? lr.getPromptVersion() : "unknown")
            .distinct().sorted()
            .collect(Collectors.toList());

        sb.append("<section class=\"section\">");
        sb.append("<h2 class=\"section-title\">All Findings</h2>");
        sb.append("<p style=\"font-size:12px;color:var(--muted);margin-bottom:14px\">")
          .append(findings.size()).append(" findings &nbsp;&middot;&nbsp; ")
          .append(totalReviewed).append(" manually reviewed &nbsp;&middot;&nbsp; ")
          .append(totalLlm).append(" LLM triaged");
        if (versions.size() > 1)
            sb.append(" across ").append(versions.size()).append(" prompt versions");
        sb.append(". Highlighted rows indicate a verdict mismatch on at least one version.</p>");

        sb.append("<div class=\"overflow-x\"><table class=\"findings-table\">");
        sb.append("<thead><tr>")
          .append("<th>#</th><th>Scanner</th><th>Severity</th><th>Rule ID</th>")
          .append("<th>File / Line</th><th>Manual Verdict</th>");
        for (String ver : versions)
            sb.append("<th>").append(esc(ver)).append("</th>");
        sb.append("</tr></thead><tbody>");

        int rowNum = 1;
        for (Finding f : findings) {
            ManualReview rev    = reviewsByFindingId.get(f.getId());
            java.util.Map<String, LlmResult> verMap =
                verByFinding.getOrDefault(f.getId(), java.util.Map.of());

            boolean anyMismatch = rev != null && verMap.values().stream()
                .anyMatch(lr -> lr.getLlmVerdict() != rev.getVerdict());

            sb.append("<tr").append(anyMismatch ? " class=\"mismatch\"" : "").append(">");

            sb.append("<td class=\"num\">").append(rowNum++).append("</td>");
            sb.append("<td>").append(scannerBadge(f.getSource() != null ? f.getSource().name() : "")).append("</td>");
            sb.append("<td>").append(severityBadge(f.getSeverity() != null ? f.getSeverity().name() : "INFO")).append("</td>");
            sb.append("<td class=\"mono\">").append(esc(truncate(f.getRuleId(), 40))).append("</td>");
            String fileLine = f.getFilePath() != null ? shortPath(f.getFilePath()) : "";
            if (f.getLineNumber() != null) fileLine += ":" + f.getLineNumber();
            sb.append("<td class=\"mono file-cell\">").append(esc(fileLine)).append("</td>");
            sb.append("<td>")
              .append(rev != null ? verdictBadge(rev.getVerdict())
                                  : "<span class=\"badge badge-none\">&mdash;</span>")
              .append("</td>");

            for (String ver : versions) {
                LlmResult vr = verMap.get(ver);
                if (vr == null) {
                    sb.append("<td><span class=\"badge badge-none\">&mdash;</span></td>");
                } else {
                    boolean match = rev != null && vr.getLlmVerdict() == rev.getVerdict();
                    String indicator = rev == null ? ""
                        : match ? " <span class=\"match-ok\" style=\"font-size:11px\">&#x2713;</span>"
                                : " <span class=\"match-no\" style=\"font-size:11px\">&#x2717;</span>";
                    sb.append("<td>")
                      .append(verdictBadge(vr.getLlmVerdict()))
                      .append(indicator)
                      .append(" <span class=\"conf\">").append(vr.getConfidence()).append("%</span>")
                      .append("</td>");
                }
            }

            sb.append("</tr>\n");
        }

        sb.append("</tbody></table></div></section>\n\n");

        // ── Footer ─────────────────────────────────────────────────────────
        sb.append("<footer style=\"border-top:1px solid var(--border);padding-top:16px;")
          .append("display:flex;justify-content:space-between;font-size:11px;color:var(--muted)\">")
          .append("<span>VulnTriage &mdash; Evaluation Report &mdash; ").append(date).append("</span>")
          .append("<span>github.com/MaheshNair1999/vulntriage</span>")
          .append("</footer>");

        sb.append("\n</div></body></html>");
        return sb.toString();
    }

    private ConfusionMatrix computeVerMatrix(List<LlmResult> results, Map<Long, ManualReview> reviewMap) {
        Map<Long, LlmResult> deduped = new java.util.LinkedHashMap<>();
        for (LlmResult lr : results)
            deduped.merge(lr.getFindingId(), lr, (a, b) -> a.getId() > b.getId() ? a : b);
        ConfusionMatrix matrix = new ConfusionMatrix();
        for (LlmResult lr : deduped.values()) {
            ManualReview mr = reviewMap.get(lr.getFindingId());
            if (mr == null) continue;
            matrix.increment(mr.getVerdict(), lr.getLlmVerdict());
        }
        return matrix;
    }

    private String matrixHtml(ConfusionMatrix cm) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p style=\"font-size:11px;color:var(--muted);margin:12px 0 6px;font-style:italic\">")
          .append("Confusion matrix &nbsp;&middot;&nbsp; Rows = manual verdict &nbsp;&middot;&nbsp; Columns = LLM verdict</p>");
        sb.append("<div class=\"matrix-wrap\"><table class=\"matrix\">");
        sb.append("<tr><th></th><th>LLM: TP</th><th>LLM: FP</th><th>LLM: REVIEW</th><th>Row Total</th></tr>");
        Verdict[] vds = {Verdict.TP, Verdict.FP, Verdict.REVIEW};
        String[] vdLabels = {"TP", "FP", "REVIEW"};
        String[] diagClasses = {"diag-tp", "diag-fp", "diag-rv"};
        for (int i = 0; i < vds.length; i++) {
            sb.append("<tr><th>Manual: ").append(vdLabels[i]).append("</th>");
            for (int j = 0; j < vds.length; j++) {
                String cls = (i == j) ? diagClasses[i] : "off";
                sb.append("<td class=\"").append(cls).append("\">")
                  .append(cm.get(vds[i], vds[j])).append("</td>");
            }
            sb.append("<td class=\"total\">").append(cm.totalManual(vds[i])).append("</td></tr>");
        }
        sb.append("<tr><th>Col Total</th>");
        for (Verdict v : vds) sb.append("<td class=\"total\">").append(cm.totalLlm(v)).append("</td>");
        sb.append("<td class=\"total\">").append(cm.total()).append("</td></tr>");
        sb.append("</table></div>");
        return sb.toString();
    }

    // ── HTML helpers ───────────────────────────────────────────────────────

    private String metaItem(String label, String value) {
        return "<div class=\"cover-meta-item\"><label>" + label + "</label><span>" + value + "</span></div>";
    }

    private String metricCard(String name, String value, String color, String desc) {
        return "<div class=\"metric-card\">"
            + "<div class=\"metric-bar\" style=\"background:" + color + "\"></div>"
            + "<div class=\"metric-val\" style=\"color:" + color + "\">" + value + "</div>"
            + "<div class=\"metric-name\">" + name + "</div>"
            + "<div class=\"metric-desc\">" + desc + "</div>"
            + "</div>";
    }

    private String scCard(String value, String label, String color) {
        return "<div class=\"sc-card\">"
            + "<div class=\"sc-val\" style=\"color:" + color + "\">" + value + "</div>"
            + "<div class=\"sc-name\">" + label + "</div>"
            + "</div>";
    }

    private String verdictBadge(Verdict v) {
        if (v == null) return "<span class=\"badge badge-none\">&mdash;</span>";
        return switch (v) {
            case TP     -> "<span class=\"badge badge-tp\">TP</span>";
            case FP     -> "<span class=\"badge badge-fp\">FP</span>";
            case REVIEW -> "<span class=\"badge badge-rv\">REVIEW</span>";
        };
    }

    private String severityBadge(String sev) {
        String cls = switch (sev) {
            case "ERROR"   -> "badge-sev-error";
            case "WARNING" -> "badge-sev-warning";
            default        -> "badge-sev-info";
        };
        return "<span class=\"badge " + cls + "\">" + sev + "</span>";
    }

    private String scannerBadge(String src) {
        String color = switch (src) {
            case "TRIVY"     -> "#92400E";
            case "GITLEAKS"  -> "#B45309";
            case "CODEQL"    -> "#065F46";
            case "SONARQUBE" -> "#991B1B";
            default          -> "#1E40AF";
        };
        return "<span style=\"font-size:10px;font-weight:700;color:" + color + ";font-family:'Courier New',monospace\">" + src + "</span>";
    }

    private String scannerColor(ScannerType type) {
        return switch (type) {
            case SEMGREP   -> "#1E40AF";
            case TRIVY     -> "#92400E";
            case GITLEAKS  -> "#B45309";
            case CODEQL    -> "#065F46";
            case SONARQUBE -> "#991B1B";
        };
    }

    private String pct(double v) {
        return String.format("%.2f%%", v * 100);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String shortPath(String path) {
        if (path == null) return "";
        // Show only last 3 path segments to keep table readable
        String[] parts = path.replace("\\", "/").split("/");
        if (parts.length <= 3) return path.replace("\\", "/");
        return "…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }
}
