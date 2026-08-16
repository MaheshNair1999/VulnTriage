package com.vulntriage.ui.evaluation;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.EvaluationRun;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.ManualReview;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.evaluation.ConfusionMatrix;
import com.vulntriage.evaluation.EvaluationReport;
import com.vulntriage.evaluation.MetricsCalculator;
import com.vulntriage.export.CsvExporter;
import com.vulntriage.export.HtmlReportExporter;
import com.vulntriage.export.JsonExporter;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.ButtonBar;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;
import java.util.Optional;
import static com.vulntriage.config.ThemeColors.*;

/**
 * Evaluation screen — shows the full evaluation metrics for a triage run.
 *
 * Displays:
 *   - Run selector (choose which evaluation run to inspect)
 *   - 3x3 confusion matrix
 *   - All 6 key metrics as stat cards
 *   - Export buttons (JSON, CSV)
 */
public class EvaluationView {


    private final AppContext       ctx        = AppContext.getInstance();
    private final MetricsCalculator calculator = new MetricsCalculator();

    private ComboBox<RunItem>  runSelector;
    private VBox               metricsArea;
    private VBox               matrixArea;
    private VBox               scannerArea;
    private VBox               compareArea;
    private VBox               versionArea;
    private VBox               totalArea;
    private Label              statusLabel;
    private String             selectedScanner = "All Scanners";
    private long               currentRunId    = -1;

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");
        root.getChildren().addAll(buildHeader(), buildBody());
        return root;
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox(12);
        header.setPadding(new Insets(18, 28, 14, 28));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 1 0;");

        VBox titleBlock = new VBox(2);
        Label title = new Label("Evaluation");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label("Compare LLM verdicts against manual ground truth.");
        sub.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        titleBlock.getChildren().addAll(title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Run selector
        runSelector = new ComboBox<>();
        runSelector.setPrefWidth(280);
        runSelector.setPromptText("Select an evaluation run…");
        runSelector.setStyle("-fx-font-size: 12px;");
        loadRuns();

        Button loadBtn = primaryBtn("Load Results");
        loadBtn.setOnAction(e -> loadSelectedRun());

        Button exportJsonBtn   = secondaryBtn("⬇  Export JSON");
        Button exportCsvBtn    = secondaryBtn("⬇  Export CSV");
        Button exportReportBtn = secondaryBtn("⬇  Export Report");
        exportJsonBtn  .setOnAction(e -> exportJson());
        exportCsvBtn   .setOnAction(e -> exportCsv());
        exportReportBtn.setOnAction(e -> exportHtmlReport());

        Button deleteBtn = deleteBtn("⊗  Delete Run");
        deleteBtn.setOnAction(e -> deleteSelectedRun());

        header.getChildren().addAll(
            titleBlock, spacer,
            runSelector, loadBtn, exportJsonBtn, exportCsvBtn, exportReportBtn, deleteBtn
        );
        return header;
    }

    // ── Body ───────────────────────────────────────────────────────────────

    private ScrollPane buildBody() {
        VBox body = new VBox(24);
        body.setPadding(new Insets(24, 32, 24, 32));
        body.setStyle("-fx-background-color: " + BG + ";");

        statusLabel = new Label("Select an evaluation run and click Load Results.");
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + MUTED + ";");

        metricsArea  = new VBox(16);
        matrixArea   = new VBox(12);
        scannerArea  = new VBox(12);
        compareArea  = new VBox(12);
        versionArea  = new VBox(16);
        totalArea    = new VBox(16);

        body.getChildren().addAll(statusLabel, metricsArea, scannerArea, compareArea, versionArea, totalArea);

        // Auto-render version comparison and evaluations whenever data is available
        javafx.application.Platform.runLater(() -> {
            renderVersionComparison();
            renderVersionBreakdown();
            renderTotalEvaluation();
        });

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + BG
            + "; -fx-background: " + BG + "; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    // ── Load & compute ─────────────────────────────────────────────────────

    private void loadRuns() {
        List<EvaluationRun> runs = ctx.evalRepo().findAll();
        runSelector.setItems(FXCollections.observableArrayList(
            runs.stream().map(RunItem::new).toList()
        ));
        if (!runSelector.getItems().isEmpty()) {
            runSelector.getSelectionModel().selectFirst();
        }
    }

    private java.util.Set<Long> validFindingIds() {
        return ctx.repositoryRepo().findAll().stream()
            .flatMap(r -> ctx.findingRepo().findByRepositoryId(r.getId()).stream())
            .map(Finding::getId)
            .collect(java.util.stream.Collectors.toSet());
    }

    private void loadSelectedRun() {
        RunItem selected = runSelector.getValue();
        if (selected == null) {
            statusLabel.setText("No run selected.");
            return;
        }

        EvaluationReport report = calculator.compute(
            selected.run.getId(), ctx.llmRepo(), ctx.reviewRepo(), ctx.evalRepo(),
            validFindingIds());

        if (report.getTotalCompared() == 0) {
            statusLabel.setText(
                "No matched pairs found for this run.\n"
                + "Make sure you have both manual reviews AND LLM results for the same findings.\n"
                + "Complete manual review first, then run LLM triage.");
            metricsArea.getChildren().clear();
            scannerArea.getChildren().clear();
            return;
        }

        currentRunId = selected.run.getId();
        statusLabel.setText("Showing results for: " + selected.run.getName()
            + "  ·  " + report.getTotalCompared() + " finding pairs compared");

        renderMetrics(report);
        renderScannerBreakdown();
        renderVersionComparison();
        renderVersionBreakdown();
        renderTotalEvaluation();
    }

    // ── Metrics cards ──────────────────────────────────────────────────────

    private void renderMetrics(EvaluationReport report) {
        metricsArea.getChildren().clear();

        Label heading = new Label("Key Metrics");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        // Row 1: the security-critical metrics
        HBox row1 = new HBox(16);
        row1.getChildren().addAll(
            metricCard("TP Recall",
                pct(report.getTpRecall()),
                GREEN,
                "LLM caught " + pct(report.getTpRecall()) + " of real vulnerabilities",
                report.getTpRecall() >= 0.7),

            metricCard("False Negative Rate",
                pct(report.getFalseNegativeRate()),
                report.getFalseNegativeRate() == 0 ? GREEN : RED,
                "Real bugs dismissed as safe by LLM",
                report.getFalseNegativeRate() == 0.0),

            metricCard("Overall Accuracy",
                pct(report.getAccuracy()),
                BLUE,
                "Agreement with manual verdicts across all classes",
                true)
        );
        row1.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        // Row 2: precision and agreement metrics
        HBox row2 = new HBox(16);
        row2.getChildren().addAll(
            metricCard("TP Precision",
                pct(report.getTpPrecision()),
                AMBER,
                "Of LLM's TP predictions, how many were correct",
                true),

            metricCard("FP Agreement",
                pct(report.getFpAgreement()),
                AMBER,
                "LLM correctly identified false positives",
                true),

            metricCard("FP Rate",
                pct(report.getFalsePositiveRate()),
                report.getFalsePositiveRate() == 0 ? GREEN : RED,
                "Noise wrongly escalated as real bugs — lower is better",
                report.getFalsePositiveRate() == 0.0),

            metricCard("REVIEW Agreement",
                pct(report.getReviewAgreement()),
                MUTED,
                "LLM used REVIEW verdict on uncertain findings",
                true)
        );
        row2.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        // Interpretation note
        Label note = new Label(
            "Note: LLM confidence scores are self-reported by the model and are not calibrated "
            + "probabilities. TP Recall and False Negative Rate are the most security-relevant "
            + "metrics — low FNR means the LLM rarely dismisses real vulnerabilities.");
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
            + "; -fx-font-style: italic;");
        note.setWrapText(true);

        metricsArea.getChildren().addAll(heading, row1, row2, note);
    }

    private VBox metricCard(String label, String value,
                            String color, String description, boolean good) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 8, 0, 0, 2);");

        Rectangle bar = new Rectangle(32, 4);
        bar.setFill(Color.web(color));
        bar.setArcWidth(4); bar.setArcHeight(4);

        Label valLabel = new Label(value);
        valLabel.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + color + "; -fx-font-family: 'Courier New';");

        Label nameLabel = new Label(label);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + MUTED + ";");

        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #9CA3AF;");
        descLabel.setWrapText(true);

        card.getChildren().addAll(bar, valLabel, nameLabel, descLabel);
        return card;
    }

    // ── Confusion matrix ───────────────────────────────────────────────────

    private void renderMatrix(ConfusionMatrix matrix) {
        matrixArea.getChildren().clear();

        Label heading = new Label("Confusion Matrix");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label("Rows = manual verdict  ·  Columns = LLM verdict");
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");

        VBox matrixCard = new VBox(8);
        matrixCard.getChildren().add(buildMatrixGrid(matrix));
        matrixCard.setMaxWidth(600);

        matrixArea.getChildren().addAll(heading, sub, matrixCard);
    }

    private GridPane buildMatrixGrid(ConfusionMatrix matrix) {
        GridPane grid = new GridPane();
        grid.setHgap(3); grid.setVgap(3);
        grid.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10; "
            + "-fx-padding: 20; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 8, 0, 0, 2);");

        String[] labels = {"TP", "FP", "REVIEW"};
        String[] colors = {GREEN, RED, AMBER};

        grid.add(headerCell(""),            0, 0);
        grid.add(headerCell("LLM: TP"),     1, 0);
        grid.add(headerCell("LLM: FP"),     2, 0);
        grid.add(headerCell("LLM: REVIEW"), 3, 0);
        grid.add(headerCell("Row Total"),   4, 0);

        Verdict[] verdicts = {Verdict.TP, Verdict.FP, Verdict.REVIEW};
        for (int i = 0; i < verdicts.length; i++) {
            grid.add(headerCell("Manual: " + labels[i]), 0, i + 1);
            for (int j = 0; j < verdicts.length; j++) {
                grid.add(matrixCell(matrix.get(verdicts[i], verdicts[j]), i == j, colors[i]), j + 1, i + 1);
            }
            grid.add(totalCell(matrix.totalManual(verdicts[i])), 4, i + 1);
        }

        grid.add(headerCell("Col Total"),                    0, 4);
        grid.add(totalCell(matrix.totalLlm(Verdict.TP)),     1, 4);
        grid.add(totalCell(matrix.totalLlm(Verdict.FP)),     2, 4);
        grid.add(totalCell(matrix.totalLlm(Verdict.REVIEW)), 3, 4);
        grid.add(totalCell(matrix.total()),                  4, 4);

        return grid;
    }

    private Label headerCell(String text) {
        Label l = new Label(text);
        l.setPrefWidth(120); l.setPrefHeight(36);
        l.setAlignment(Pos.CENTER);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + MUTED + "; -fx-padding: 6 10;");
        return l;
    }

    private VBox matrixCell(int value, boolean diagonal, String accentColor) {
        VBox cell = new VBox();
        cell.setAlignment(Pos.CENTER);
        cell.setPrefWidth(120); cell.setPrefHeight(56);
        cell.setStyle("-fx-background-color: "
            + (diagonal ? lighten(accentColor) : "" + SURFACE + "") + "; "
            + "-fx-background-radius: 6;");

        Label val = new Label(String.valueOf(value));
        val.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; "
            + "-fx-font-family: 'Courier New'; "
            + "-fx-text-fill: " + (diagonal ? accentColor : TEXT) + ";");
        cell.getChildren().add(val);
        return cell;
    }

    private Label totalCell(int value) {
        Label l = new Label(String.valueOf(value));
        l.setPrefWidth(120); l.setPrefHeight(36);
        l.setAlignment(Pos.CENTER);
        l.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + TEXT + "; -fx-padding: 6 10;");
        return l;
    }

    // ── Prompt version comparison ──────────────────────────────────────────

    private record VersionMetrics(int tpTp, int tpTotal, int fpFp, int fpTotal, int predTp, int total) {
        double tpRecall()    { return tpTotal > 0 ? (double) tpTp / tpTotal : 0; }
        double fpAgreement() { return fpTotal > 0 ? (double) fpFp / fpTotal : 0; }
        double tpPrecision() { return predTp  > 0 ? (double) tpTp / predTp  : 0; }
    }

    private VersionMetrics computeVersionMetrics(List<LlmResult> results,
                                                  java.util.Map<Long, ManualReview> reviewMap) {
        // Deduplicate: if multiple results per finding, keep latest (highest id)
        java.util.Map<Long, LlmResult> deduped = new java.util.LinkedHashMap<>();
        for (LlmResult lr : results) deduped.merge(lr.getFindingId(), lr,
            (a, b) -> a.getId() > b.getId() ? a : b);

        int tpTp = 0, tpTotal = 0, fpFp = 0, fpTotal = 0, predTp = 0;
        for (LlmResult lr : deduped.values()) {
            ManualReview mr = reviewMap.get(lr.getFindingId());
            if (mr == null) continue;
            Verdict human = mr.getVerdict();
            Verdict llm   = lr.getLlmVerdict();
            if (human == Verdict.TP) { tpTotal++; if (llm == Verdict.TP) tpTp++; }
            if (human == Verdict.FP) { fpTotal++; if (llm == Verdict.FP) fpFp++; }
            if (llm == Verdict.TP) predTp++;
        }
        return new VersionMetrics(tpTp, tpTotal, fpFp, fpTotal, predTp, deduped.size());
    }

    private ConfusionMatrix computeVersionMatrix(List<LlmResult> results,
                                                  java.util.Map<Long, ManualReview> reviewMap) {
        java.util.Map<Long, LlmResult> deduped = new java.util.LinkedHashMap<>();
        for (LlmResult lr : results) deduped.merge(lr.getFindingId(), lr,
            (a, b) -> a.getId() > b.getId() ? a : b);

        ConfusionMatrix matrix = new ConfusionMatrix();
        for (LlmResult lr : deduped.values()) {
            ManualReview mr = reviewMap.get(lr.getFindingId());
            if (mr == null) continue;
            matrix.increment(mr.getVerdict(), lr.getLlmVerdict());
        }
        return matrix;
    }

    private void renderVersionComparison() {
        compareArea.getChildren().clear();

        // Discover all versions that actually have results
        List<String> allVersions = ctx.llmRepo().findAll().stream()
            .map(LlmResult::getPromptVersion)
            .filter(v -> v != null && !v.isBlank())
            .distinct().sorted()
            .collect(java.util.stream.Collectors.toList());

        if (allVersions.size() < 2) return;

        java.util.Map<Long, ManualReview> reviewMap = ctx.reviewRepo().findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                ManualReview::getFindingId, r -> r, (a, b) -> b));

        Label heading = new Label("Prompt Version Comparison");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        VBox tableContainer = new VBox(8);

        if (allVersions.size() == 2) {
            String baseVer = allVersions.get(0);
            String vsVer   = allVersions.get(1);
            String baseName = ctx.promptTemplateRepo().findByVersion(baseVer)
                .map(t -> baseVer + ": " + t.getName()).orElse(baseVer);
            String vsName = ctx.promptTemplateRepo().findByVersion(vsVer)
                .map(t -> vsVer + ": " + t.getName()).orElse(vsVer);
            Label sub = new Label(baseName + "  ·  " + vsName);
            sub.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
            sub.setWrapText(true);
            buildComparisonTable(tableContainer, baseVer, vsVer, reviewMap);
            compareArea.getChildren().addAll(heading, sub, tableContainer);
        } else {
            // Multi-version: show pickers
            Label baseLabel = new Label("Base:");
            baseLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
            Label vsLabel = new Label("Compare to:");
            vsLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

            ComboBox<String> baseBox = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(allVersions));
            baseBox.setValue(allVersions.get(0));
            baseBox.setStyle("-fx-font-size: 12px;");

            ComboBox<String> vsBox = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(allVersions));
            vsBox.setValue(allVersions.get(allVersions.size() - 1));
            vsBox.setStyle("-fx-font-size: 12px;");

            HBox pickerRow = new HBox(10, baseLabel, baseBox, vsLabel, vsBox);
            pickerRow.setAlignment(Pos.CENTER_LEFT);
            pickerRow.setPadding(new Insets(0, 0, 4, 0));

            Runnable rebuild = () -> {
                String base = baseBox.getValue();
                String vs   = vsBox.getValue();
                if (base == null || vs == null || base.equals(vs)) {
                    tableContainer.getChildren().clear();
                    Label err = new Label("Select two different versions to compare.");
                    err.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
                    tableContainer.getChildren().add(err);
                    return;
                }
                tableContainer.getChildren().clear();
                buildComparisonTable(tableContainer, base, vs, reviewMap);
            };

            baseBox.setOnAction(e -> rebuild.run());
            vsBox.setOnAction(e -> rebuild.run());
            rebuild.run();

            compareArea.getChildren().addAll(heading, pickerRow, tableContainer);
        }
    }

    private void buildComparisonTable(VBox container, String baseVer, String vsVer,
            java.util.Map<Long, ManualReview> reviewMap) {

        List<LlmResult> baseResults = ctx.llmRepo().findAllByPromptVersion(baseVer);
        List<LlmResult> vsResults   = ctx.llmRepo().findAllByPromptVersion(vsVer);

        if (baseResults.isEmpty() || vsResults.isEmpty()) {
            Label msg = new Label("No results for one of the selected versions.");
            msg.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
            container.getChildren().add(msg);
            return;
        }

        // Intersect finding IDs for a fair comparison
        java.util.Set<Long> sharedIds = vsResults.stream()
            .map(LlmResult::getFindingId).collect(java.util.stream.Collectors.toSet());
        List<LlmResult> baseFiltered = baseResults.stream()
            .filter(r -> sharedIds.contains(r.getFindingId())).toList();

        VersionMetrics mBase = computeVersionMetrics(baseFiltered, reviewMap);
        VersionMetrics mVs   = computeVersionMetrics(vsResults,    reviewMap);

        String baseName = ctx.promptTemplateRepo().findByVersion(baseVer)
            .map(t -> baseVer + ": " + t.getName()).orElse(baseVer);
        String vsName = ctx.promptTemplateRepo().findByVersion(vsVer)
            .map(t -> vsVer + ": " + t.getName()).orElse(vsVer);
        Label sub = new Label(baseName + "  ·  " + vsName);
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        sub.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(2); grid.setVgap(2);
        grid.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10; "
            + "-fx-padding: 20; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 8, 0, 0, 2);");

        grid.add(compHeader("Metric"),                                     0, 0);
        grid.add(colSep(compHeader(baseVer)),                              1, 0);
        grid.add(colSep(compHeader(vsVer)),                                2, 0);
        grid.add(colSep(compHeader("Δ (" + vsVer + " − " + baseVer + ")")), 3, 0);

        record Row(String label, double base, double vs, boolean higherIsBetter) {}
        List<Row> rows = List.of(
            new Row("TP Recall",    mBase.tpRecall(),    mVs.tpRecall(),    true),
            new Row("FP Agreement", mBase.fpAgreement(), mVs.fpAgreement(), true),
            new Row("TP Precision", mBase.tpPrecision(), mVs.tpPrecision(), true)
        );

        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            double delta = r.vs() - r.base();
            String sign  = delta >= 0 ? "+" : "";
            String deltaColor = (delta >= 0) == r.higherIsBetter() ? GREEN : RED;

            grid.add(compLabel(r.label()),                               0, i + 1);
            grid.add(colSep(compValue(pct(r.base()), BLUE)),             1, i + 1);
            grid.add(colSep(compValue(pct(r.vs()),   PURPLE)),           2, i + 1);
            grid.add(colSep(compValue(sign + pct(delta), deltaColor)),   3, i + 1);
        }

        grid.add(compLabel("Findings Processed"),                        0, rows.size() + 1);
        grid.add(colSep(compValue(String.valueOf(mBase.total()), BLUE)), 1, rows.size() + 1);
        grid.add(colSep(compValue(String.valueOf(mVs.total()), PURPLE)), 2, rows.size() + 1);
        grid.add(colSep(compLabel("—")),                                 3, rows.size() + 1);

        VBox card = new VBox(8);
        card.getChildren().add(grid);
        card.setMaxWidth(700);

        container.getChildren().addAll(sub, card);
    }

    private Label compHeader(String text) {
        Label l = new Label(text);
        l.setPrefWidth(150); l.setPrefHeight(32);
        l.setAlignment(Pos.CENTER);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + MUTED + "; -fx-padding: 4 10; "
            + "-fx-background-color: " + SURFACE + "; -fx-background-radius: 4;");
        return l;
    }

    private Label compLabel(String text) {
        Label l = new Label(text);
        l.setPrefWidth(150); l.setPrefHeight(40);
        l.setAlignment(Pos.CENTER_LEFT);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT + "; -fx-padding: 4 10;");
        return l;
    }

    private Label compValue(String text, String color) {
        Label l = new Label(text);
        l.setPrefWidth(150); l.setPrefHeight(40);
        l.setAlignment(Pos.CENTER);
        l.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; "
            + "-fx-font-family: 'Courier New'; "
            + "-fx-text-fill: " + color + "; -fx-padding: 4 10;");
        return l;
    }

    private Label colSep(Label l) {
        l.setStyle(l.getStyle()
            + " -fx-border-color: transparent transparent transparent " + BORDER + ";"
            + " -fx-border-width: 0 0 0 1;");
        return l;
    }

    // ── Version-based evaluation ───────────────────────────────────────────

    private void renderVersionBreakdown() {
        versionArea.getChildren().clear();

        List<LlmResult> allLlm = ctx.llmRepo().findAll();
        if (allLlm.isEmpty()) return;

        java.util.Map<Long, ManualReview> reviewMap = ctx.reviewRepo().findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                ManualReview::getFindingId, r -> r, (a, b) -> b));

        // Group by prompt version (preserving insertion order)
        java.util.Map<String, List<LlmResult>> byVersion = new java.util.LinkedHashMap<>();
        for (LlmResult lr : allLlm) {
            String v = lr.getPromptVersion() != null ? lr.getPromptVersion() : "unknown";
            byVersion.computeIfAbsent(v, k -> new java.util.ArrayList<>()).add(lr);
        }

        Label heading = new Label("Version-Based Evaluation");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label(
            "Per-prompt-version metrics across all findings triaged by each version");
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        sub.setWrapText(true);

        versionArea.getChildren().addAll(heading, sub);

        for (java.util.Map.Entry<String, List<LlmResult>> entry : byVersion.entrySet()) {
            String version = entry.getKey();
            VersionMetrics m = computeVersionMetrics(entry.getValue(), reviewMap);
            ConfusionMatrix cm = computeVersionMatrix(entry.getValue(), reviewMap);

            VBox versionBlock = new VBox(12);

            Label versionLbl = new Label(version + "  (" + m.total() + " findings triaged)");
            versionLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + BLUE + ";");

            HBox cards = new HBox(16);
            cards.getChildren().addAll(
                metricCard("TP Recall",
                    pct(m.tpRecall()),
                    GREEN,
                    m.tpTp() + " / " + m.tpTotal() + " real vulnerabilities caught",
                    m.tpRecall() >= 0.7),
                metricCard("FP Agreement",
                    pct(m.fpAgreement()),
                    AMBER,
                    m.fpFp() + " / " + m.fpTotal() + " false positives agreed",
                    m.fpAgreement() >= 0.5),
                metricCard("TP Precision",
                    pct(m.tpPrecision()),
                    AMBER,
                    m.tpTp() + " / " + m.predTp() + " TP predictions correct",
                    m.tpPrecision() >= 0.2)
            );
            cards.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

            Label matrixSub = new Label("Confusion matrix  ·  Rows = manual verdict  ·  Columns = LLM verdict");
            matrixSub.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");

            VBox matrixCard = new VBox(4);
            matrixCard.getChildren().addAll(matrixSub, buildMatrixGrid(cm));
            matrixCard.setMaxWidth(600);

            versionBlock.getChildren().addAll(versionLbl, cards, matrixCard);
            versionArea.getChildren().add(versionBlock);
        }
    }

    // ── Total evaluation ───────────────────────────────────────────────────

    private void renderTotalEvaluation() {
        totalArea.getChildren().clear();

        List<LlmResult> allLlm = ctx.llmRepo().findAll();
        if (allLlm.isEmpty()) return;

        java.util.Map<Long, ManualReview> reviewMap = ctx.reviewRepo().findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                ManualReview::getFindingId, r -> r, (a, b) -> b));

        VersionMetrics m = computeVersionMetrics(allLlm, reviewMap);

        Label heading = new Label("Total Evaluation");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label(
            "Aggregate metrics across all LLM results in the database — all versions combined, "
            + "latest result per finding");
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        sub.setWrapText(true);

        HBox cards = new HBox(16);
        cards.getChildren().addAll(
            metricCard("TP Recall",
                pct(m.tpRecall()),
                GREEN,
                m.tpTp() + " / " + m.tpTotal() + " real vulnerabilities caught",
                m.tpRecall() >= 0.7),
            metricCard("FP Agreement",
                pct(m.fpAgreement()),
                AMBER,
                m.fpFp() + " / " + m.fpTotal() + " false positives agreed",
                m.fpAgreement() >= 0.5),
            metricCard("TP Precision",
                pct(m.tpPrecision()),
                AMBER,
                m.tpTp() + " / " + m.predTp() + " TP predictions correct",
                m.tpPrecision() >= 0.2),
            metricCard("Findings Triaged",
                String.valueOf(m.total()),
                BLUE,
                "Unique findings with both LLM result and manual review",
                true)
        );
        cards.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        totalArea.getChildren().addAll(heading, sub, cards);
    }

    // ── Scanner breakdown ──────────────────────────────────────────────────

    private static final List<String> SCANNER_OPTIONS = List.of(
        "All Scanners", "SEMGREP", "TRIVY", "GITLEAKS", "CODEQL", "SONARQUBE"
    );

    private void renderScannerBreakdown() {
        scannerArea.getChildren().clear();
        if (currentRunId < 0) return;

        // Header row with dropdown
        HBox header = new HBox(16);
        header.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Scanner Breakdown");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ComboBox<String> picker = new ComboBox<>(FXCollections.observableArrayList(SCANNER_OPTIONS));
        picker.setValue(selectedScanner);
        picker.setStyle("-fx-font-size: 12px;");
        picker.setPrefWidth(160);
        picker.setOnAction(e -> {
            if (picker.getValue() != null) {
                selectedScanner = picker.getValue();
                renderScannerBreakdown();
            }
        });
        header.getChildren().addAll(heading, spacer, picker);

        // All findings (across all repos) — no findAll() on repo interface, so aggregate
        List<Finding> allFindings = ctx.repositoryRepo().findAll().stream()
            .flatMap(r -> ctx.findingRepo().findByRepositoryId(r.getId()).stream())
            .toList();

        // Index findings by ID for fast lookup; used to skip orphaned reviews
        java.util.Map<Long, Finding> findingById = allFindings.stream()
            .collect(java.util.stream.Collectors.toMap(Finding::getId, f -> f));

        // Deduplicate reviews by finding ID (keep last) and skip orphaned reviews
        // (reviews whose finding was deleted from the DB inflate counts otherwise)
        java.util.Map<Long, ManualReview> reviewMap = ctx.reviewRepo().findAll().stream()
            .filter(mr -> findingById.containsKey(mr.getFindingId()))
            .collect(java.util.stream.Collectors.toMap(
                ManualReview::getFindingId, r -> r, (a, b) -> b));

        // Apply scanner filter
        List<ManualReview> reviews = reviewMap.values().stream().filter(mr -> {
            if ("All Scanners".equals(selectedScanner)) return true;
            Finding f = findingById.get(mr.getFindingId());
            return f != null && f.getSource().name().equalsIgnoreCase(selectedScanner);
        }).toList();

        // Count scanner's total findings in DB
        long totalFindings = "All Scanners".equals(selectedScanner)
            ? allFindings.size()
            : allFindings.stream()
                .filter(f -> f.getSource().name().equalsIgnoreCase(selectedScanner)).count();

        long manualTP  = reviews.stream().filter(r -> r.getVerdict() == Verdict.TP).count();
        long manualFP  = reviews.stream().filter(r -> r.getVerdict() == Verdict.FP).count();
        long manualRev = reviews.stream().filter(r -> r.getVerdict() == Verdict.REVIEW).count();
        long reviewed  = reviews.size();

        // LLM results scoped to the selected run
        java.util.Map<Long, LlmResult> llmByFinding = reviews.stream()
            .map(mr -> ctx.llmRepo().findByFindingIdAndRunId(mr.getFindingId(), currentRunId))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(java.util.stream.Collectors.toMap(LlmResult::getFindingId, l -> l,
                (a, b) -> b));

        List<LlmResult> llmForScanner = reviews.stream()
            .map(mr -> llmByFinding.get(mr.getFindingId()))
            .filter(l -> l != null)
            .toList();

        long llmTP  = llmForScanner.stream().filter(l -> l.getLlmVerdict() == Verdict.TP).count();
        long llmFP  = llmForScanner.stream().filter(l -> l.getLlmVerdict() == Verdict.FP).count();
        long llmRev = llmForScanner.stream().filter(l -> l.getLlmVerdict() == Verdict.REVIEW).count();

        // Agreement: LLM verdict matches manual verdict
        long agreed = reviews.stream().filter(mr -> {
            LlmResult llm = llmByFinding.get(mr.getFindingId());
            return llm != null && llm.getLlmVerdict() == mr.getVerdict();
        }).count();
        String agreementPct = reviewed > 0
            ? String.format("%.1f%%", (double) agreed / reviewed * 100) : "—";

        // Per-version LLM triaged counts (across all runs, scanner-filtered, deduplicated per finding)
        List<LlmResult> allLlmAll = ctx.llmRepo().findAll();
        java.util.Map<String, java.util.Set<Long>> versionFindingIds = new java.util.LinkedHashMap<>();
        for (LlmResult lr : allLlmAll) {
            Finding f = findingById.get(lr.getFindingId());
            if (!"All Scanners".equals(selectedScanner)
                    && (f == null || !f.getSource().name().equalsIgnoreCase(selectedScanner))) continue;
            String v = lr.getPromptVersion() != null ? lr.getPromptVersion() : "unknown";
            versionFindingIds.computeIfAbsent(v, k -> new java.util.HashSet<>()).add(lr.getFindingId());
        }

        // Summary stat cards
        HBox summaryCards = new HBox(16);
        summaryCards.getChildren().addAll(
            breakdownCard("Total Findings",    String.valueOf(totalFindings), BLUE,  "From this scanner in database"),
            breakdownCard("Manually Reviewed", String.valueOf(reviewed),      GREEN, "Findings with TP/FP/REVIEW verdict")
        );
        for (java.util.Map.Entry<String, java.util.Set<Long>> vEntry : versionFindingIds.entrySet()) {
            summaryCards.getChildren().add(
                breakdownCard("LLM Triaged (" + vEntry.getKey() + ")",
                    String.valueOf(vEntry.getValue().size()),
                    AMBER,
                    "Findings processed by " + vEntry.getKey())
            );
        }
        summaryCards.getChildren().add(
            breakdownCard("LLM Agreement", agreementPct, reviewed > 0 ? " + PURPLE + " : MUTED, "LLM matched manual verdict")
        );
        summaryCards.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        // Side-by-side Manual vs LLM verdict distribution
        HBox distributions = new HBox(16);
        distributions.getChildren().addAll(
            verdictDistCard("Manual Review Verdicts", manualTP, manualFP, manualRev, reviewed),
            verdictDistCard("LLM Triage Verdicts",    llmTP,    llmFP,    llmRev,    llmForScanner.size())
        );
        distributions.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        scannerArea.getChildren().addAll(header, summaryCards, distributions);
    }

    private VBox verdictDistCard(String title, long tp, long fp, long review, long total) {
        VBox card = new VBox(12);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 8, 0, 0, 2);");

        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        card.getChildren().add(titleLbl);

        record VerdictRow(String label, long count, String color) {}
        List<VerdictRow> rows = List.of(
            new VerdictRow("True Positive (TP)",  tp,     RED),
            new VerdictRow("False Positive (FP)", fp,     GREEN),
            new VerdictRow("Needs Review",        review, AMBER)
        );

        for (VerdictRow row : rows) {
            HBox rowBox = new HBox(10);
            rowBox.setAlignment(Pos.CENTER_LEFT);

            Label nameLbl = new Label(row.label());
            nameLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT + ";");
            nameLbl.setMinWidth(160);

            double pct = total > 0 ? (double) row.count() / total : 0;
            javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(pct);
            bar.setPrefWidth(Double.MAX_VALUE);
            bar.setPrefHeight(14);
            bar.setStyle("-fx-accent: " + row.color() + ";");
            HBox.setHgrow(bar, Priority.ALWAYS);

            Label cntLbl = new Label(row.count() + (total > 0 ? "  (" + String.format("%.0f%%", pct * 100) + ")" : ""));
            cntLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-min-width: 70;");

            rowBox.getChildren().addAll(nameLbl, bar, cntLbl);
            card.getChildren().add(rowBox);
        }
        return card;
    }

    private VBox breakdownCard(String label, String value, String color, String sub) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(16, 20, 16, 20));
        card.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 8, 0, 0, 2);");

        Rectangle bar = new Rectangle(28, 3);
        bar.setFill(Color.web(color));
        bar.setArcWidth(3); bar.setArcHeight(3);

        Label valLbl = new Label(value);
        valLbl.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + color + "; -fx-font-family: 'Courier New';");

        Label nameLbl = new Label(label);
        nameLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");

        Label subLbl = new Label(sub);
        subLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #9CA3AF;");
        subLbl.setWrapText(true);

        card.getChildren().addAll(bar, valLbl, nameLbl, subLbl);
        return card;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        return l;
    }

    private TextArea textArea(String content, double prefHeight) {
        TextArea ta = new TextArea(content != null ? content : "");
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefHeight(prefHeight);
        ta.setStyle("-fx-font-size: 12px; -fx-background-color: " + SURFACE + ";");
        return ta;
    }

    // ── Export ─────────────────────────────────────────────────────────────

    private void exportJson() {
        RunItem selected = runSelector.getValue();
        if (selected == null) { statusLabel.setText("Select a run first."); return; }

        FileChooser fc = new FileChooser();
        fc.setTitle("Save JSON Export");
        fc.setInitialFileName("evaluation_report.json");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = fc.showSaveDialog(null);

        if (file != null) {
            EvaluationReport report = calculator.compute(
                selected.run.getId(), ctx.llmRepo(), ctx.reviewRepo(), ctx.evalRepo(),
                validFindingIds());
            try {
                new JsonExporter().export(report, file.getAbsolutePath());
                statusLabel.setText("Exported JSON to: " + file.getAbsolutePath());
            } catch (Exception e) {
                statusLabel.setText("Export failed: " + e.getMessage());
            }
        }
    }

    private void exportCsv() {
        RunItem selected = runSelector.getValue();
        if (selected == null) { statusLabel.setText("Select a run first."); return; }

        FileChooser fc = new FileChooser();
        fc.setTitle("Save CSV Export");
        fc.setInitialFileName("triage_results.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = fc.showSaveDialog(null);

        if (file != null) {
            try {
                new CsvExporter().export(
                    selected.run.getId(), ctx.llmRepo(), ctx.reviewRepo(),
                    ctx.findingRepo(), file.getAbsolutePath());
                statusLabel.setText("Exported CSV to: " + file.getAbsolutePath());
            } catch (Exception e) {
                statusLabel.setText("Export failed: " + e.getMessage());
            }
        }
    }

    private void exportHtmlReport() {
        RunItem selected = runSelector.getValue();
        if (selected == null) { statusLabel.setText("Select a run first."); return; }

        FileChooser fc = new FileChooser();
        fc.setTitle("Save HTML Report");
        fc.setInitialFileName("vulntriage_report.html");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("HTML Report", "*.html"));
        File file = fc.showSaveDialog(null);
        if (file == null) return;

        // Gather all findings across all repos
        java.util.Set<Long> validIds = validFindingIds();
        List<Finding> allFindings = ctx.repositoryRepo().findAll().stream()
            .flatMap(r -> ctx.findingRepo().findByRepositoryId(r.getId()).stream())
            .toList();

        // Build review map (deduplicated, orphans excluded)
        java.util.Map<Long, ManualReview> reviewMap = ctx.reviewRepo().findAll().stream()
            .filter(mr -> validIds.contains(mr.getFindingId()))
            .collect(java.util.stream.Collectors.toMap(
                ManualReview::getFindingId, r -> r, (a, b) -> b));

        // Build LLM result map (orphans excluded)
        java.util.Map<Long, LlmResult> llmMap = allFindings.stream()
            .map(f -> ctx.llmRepo().findByFindingId(f.getId()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(java.util.stream.Collectors.toMap(LlmResult::getFindingId, l -> l, (a, b) -> b));

        EvaluationReport report = calculator.compute(
            selected.run.getId(), ctx.llmRepo(), ctx.reviewRepo(), ctx.evalRepo(), validIds);

        try {
            new HtmlReportExporter().export(
                selected.run.getName(), report, allFindings, reviewMap, llmMap,
                ctx.llmRepo().findAll(),
                file.getAbsolutePath());
            statusLabel.setText("Report saved to: " + file.getAbsolutePath());
        } catch (Exception e) {
            statusLabel.setText("Export failed: " + e.getMessage());
        }
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    private void deleteSelectedRun() {
        RunItem selected = runSelector.getValue();
        if (selected == null) {
            statusLabel.setText("Select a run to delete.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Delete Evaluation Run");
        confirm.setHeaderText("Permanently delete \"" + selected.run.getName() + "\"?");
        confirm.setContentText(
            "This will permanently delete the evaluation run along with all its LLM results "
            + "and sample data. This cannot be undone.");
        confirm.getButtonTypes().setAll(
            new ButtonType("Delete Permanently", ButtonBar.ButtonData.OK_DONE),
            ButtonType.CANCEL
        );

        confirm.showAndWait().ifPresent(response -> {
            if (response.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                ctx.evalRepo().deleteById(selected.run.getId());
                metricsArea.getChildren().clear();
                loadRuns();
                statusLabel.setText("Deleted \"" + selected.run.getName() + "\".");
            }
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String pct(double v) {
        return String.format("%.2f%%", v * 100);
    }

    private String lighten(String hex) {
        if (hex.equals(GREEN)) return GREEN_BG;
        if (hex.equals(RED))   return RED_BG;
        if (hex.equals(AMBER)) return AMBER_BG;
        if (hex.equals(BLUE))  return BLUE_BG;
        return NEUTRAL_BG;
    }

    private Button primaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 16;");
        return b;
    }

    private Button secondaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + DIM + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 14; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6;");
        return b;
    }

    private Button deleteBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + RED_LIGHT_BG + "; -fx-text-fill: " + RED + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 14; "
            + "-fx-border-color: " + RED_BORDER + "; -fx-border-radius: 6;");
        return b;
    }

    // ── Inner types ────────────────────────────────────────────────────────

    private static class RunItem {
        final EvaluationRun run;
        RunItem(EvaluationRun run) { this.run = run; }
        @Override public String toString() { return run.getName(); }
    }

}