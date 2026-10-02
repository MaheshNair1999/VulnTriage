package com.vulntriage.ui.dashboard;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.Repository;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.domain.enums.Verdict;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.List;
import static com.vulntriage.config.ThemeColors.*;

/**
 * Dashboard — first screen the user sees.
 *
 * Shows:
 *   - Repository selector (All Repos or a specific one)
 *   - 4 summary stat cards (total findings, reviewed, TP count, FP rate)
 *   - Bar chart: findings by severity
 *   - Pie chart: manual review distribution (TP / FP / REVIEW)
 *   - Trivy SCA section
 *
 * All data is scoped to the selected repository.
 */
public class DashboardView {


    private static final long ALL_REPOS = -1L;

    private final AppContext ctx = AppContext.getInstance();

    // The outer scroll pane — we rebuild its content on repo/scanner change
    private ScrollPane scroll;
    private long   selectedRepoId  = ALL_REPOS;

    public Node build() {
        scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + BG + "; "
            + "-fx-background: " + BG + "; -fx-border-color: transparent;");
        rebuildContent();
        return scroll;
    }

    private void rebuildContent() {
        VBox root = new VBox(24);
        root.setPadding(new Insets(28, 32, 28, 32));
        root.setStyle("-fx-background-color: " + BG + ";");

        root.getChildren().add(buildHeader());
        root.getChildren().add(buildStatCards());
        root.getChildren().add(buildChartsRow());
        root.getChildren().add(buildScannerBreakdown());

        scroll.setContent(root);
    }

    // ── Header with repo selector ──────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox(16);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBlock = new VBox(4);
        Label title = new Label("Overview");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label("Summary of all findings, reviews, and LLM triage results.");
        sub.setStyle("-fx-font-size: 13px; -fx-text-fill: " + MUTED + ";");
        titleBlock.getChildren().addAll(title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Repo selector
        ComboBox<RepoOption> repoSelector = new ComboBox<>();
        repoSelector.setStyle("-fx-font-size: 12px;");
        repoSelector.setPrefWidth(200);

        repoSelector.getItems().add(new RepoOption(ALL_REPOS, "All Repositories"));
        List<Repository> repos = ctx.repositoryRepo().findAll();
        for (Repository r : repos) {
            repoSelector.getItems().add(new RepoOption(r.getId(), r.getName()));
        }
        // Restore previous selection
        repoSelector.getItems().stream()
            .filter(o -> o.id() == selectedRepoId)
            .findFirst()
            .ifPresentOrElse(
                repoSelector::setValue,
                () -> repoSelector.setValue(repoSelector.getItems().get(0))
            );

        repoSelector.setOnAction(e -> {
            RepoOption sel = repoSelector.getValue();
            if (sel != null) {
                selectedRepoId = sel.id();
                rebuildContent();
            }
        });

        header.getChildren().addAll(titleBlock, spacer, repoSelector);
        return header;
    }

    // ── Findings for current scope ─────────────────────────────────────────

    private List<Finding> scopedFindings() {
        if (selectedRepoId == ALL_REPOS) {
            return ctx.repositoryRepo().findAll().stream()
                .flatMap(r -> ctx.findingRepo().findByRepositoryId(r.getId()).stream())
                .toList();
        }
        return ctx.findingRepo().findByRepositoryId(selectedRepoId);
    }

    // ── Stat cards ─────────────────────────────────────────────────────────

    private HBox buildStatCards() {
        List<Finding> findings = scopedFindings();
        long totalFindings = findings.size();

        // Reviews scoped to this repo's findings — join in memory to avoid new repo methods
        List<Long> findingIds = findings.stream().map(Finding::getId).toList();
        long reviewed = ctx.reviewRepo().findAll().stream()
            .filter(r -> findingIds.contains(r.getFindingId())).count();
        long tpCount  = ctx.reviewRepo().findAll().stream()
            .filter(r -> findingIds.contains(r.getFindingId()) && r.getVerdict() == Verdict.TP).count();
        long fpCount  = ctx.reviewRepo().findAll().stream()
            .filter(r -> findingIds.contains(r.getFindingId()) && r.getVerdict() == Verdict.FP).count();
        String fpRate = reviewed > 0
            ? String.format("%.1f%%", (double) fpCount / reviewed * 100) : "—";

        HBox row = new HBox(16);
        row.setFillHeight(true);
        row.getChildren().addAll(
            statCard("Total Findings",     String.valueOf(totalFindings), ACCENT,
                "All findings stored in the database"),
            statCard("Reviewed",           String.valueOf(reviewed),      GREEN,
                "Findings with a manual TP / FP / REVIEW verdict"),
            statCard("True Positives",     String.valueOf(tpCount),       RED,
                "Confirmed genuine vulnerabilities"),
            statCard("False Positive Rate", fpRate,                       AMBER,
                "FP / total reviewed findings")
        );
        row.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
        return row;
    }

    private VBox statCard(String label, String value, String accentColor, String tooltip) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(20, 24, 20, 24));
        card.setStyle("-fx-background-color: " + CARD_BG + "; "
            + "-fx-background-radius: 10px; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");

        Rectangle bar = new Rectangle(36, 4);
        bar.setFill(Color.web(accentColor));
        bar.setArcWidth(4); bar.setArcHeight(4);

        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + TEXT + "; -fx-font-family: 'Courier New';");

        Label nameLabel = new Label(label);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + MUTED + "; -fx-letter-spacing: 0.5px;");

        Label tipLabel = new Label(tooltip);
        tipLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #9CA3AF;");
        tipLabel.setWrapText(true);

        card.getChildren().addAll(bar, valueLabel, nameLabel, tipLabel);
        return card;
    }

    // ── Charts ─────────────────────────────────────────────────────────────

    private HBox buildChartsRow() {
        HBox row = new HBox(16);
        row.setFillHeight(true);
        Node bar = buildSeverityChart();
        Node pie = buildVerdictPie();
        HBox.setHgrow(bar, Priority.ALWAYS);
        HBox.setHgrow(pie, Priority.SOMETIMES);
        row.getChildren().addAll(bar, pie);
        return row;
    }

    private Node buildSeverityChart() {
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis   yAxis = new NumberAxis();
        xAxis.setLabel("Severity");
        yAxis.setLabel("Findings");

        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setTitle("Findings by Severity");
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setPrefHeight(300);
        chart.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10px; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");

        List<Finding> findings = scopedFindings();
        long info    = count(findings, Severity.INFO);
        long warning = count(findings, Severity.WARNING);
        long error   = count(findings, Severity.ERROR);

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.getData().add(new XYChart.Data<>("INFO",    info));
        series.getData().add(new XYChart.Data<>("WARNING", warning));
        series.getData().add(new XYChart.Data<>("ERROR",   error));
        chart.getData().add(series);

        javafx.application.Platform.runLater(() -> styleBarChart(chart));

        return wrapInCard(chart);
    }

    private Node buildVerdictPie() {
        PieChart chart = new PieChart();
        chart.setTitle("Review Distribution");
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setPrefSize(320, 300);

        List<Finding> findings = scopedFindings();
        List<Long> findingIds = findings.stream().map(Finding::getId).toList();
        var allReviews = ctx.reviewRepo().findAll().stream()
            .filter(r -> findingIds.contains(r.getFindingId())).toList();

        long tp  = allReviews.stream().filter(r -> r.getVerdict() == Verdict.TP).count();
        long fp  = allReviews.stream().filter(r -> r.getVerdict() == Verdict.FP).count();
        long rev = allReviews.stream().filter(r -> r.getVerdict() == Verdict.REVIEW).count();

        if (tp + fp + rev > 0) {
            chart.getData().addAll(
                new PieChart.Data("TP (" + tp + ")",      tp),
                new PieChart.Data("FP (" + fp + ")",      fp),
                new PieChart.Data("Review (" + rev + ")", rev)
            );
        } else {
            chart.getData().add(new PieChart.Data("No reviews yet", 1));
        }

        VBox card = new VBox(0);
        card.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10px; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");
        card.setPadding(new Insets(16));
        card.getChildren().add(chart);
        return card;
    }

    private VBox wrapInCard(Node content) {
        VBox card = new VBox(0);
        card.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10px; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");
        card.setPadding(new Insets(16));
        card.getChildren().add(content);
        return card;
    }

    private long count(List<Finding> findings, Severity sev) {
        return findings.stream().filter(f -> f.getSeverity() == sev).count();
    }

    private void styleBarChart(BarChart<String, Number> chart) {
        String[] colors = {"#60A5FA", "#FBBF24", "#F87171"};
        var bars = chart.lookupAll(".bar");
        int i = 0;
        for (var bar : bars) {
            bar.setStyle("-fx-bar-fill: " + colors[i % colors.length] + ";");
            i++;
        }
    }

    // ── Scanner breakdown section ──────────────────────────────────────────

    private VBox buildScannerBreakdown() {
        VBox section = new VBox(20);

        Label heading = new Label("Scanner Breakdown");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        section.getChildren().add(heading);

        List<Finding> all = scopedFindings();
        if (all.isEmpty()) {
            Label none = new Label("No findings yet. Run a scan first.");
            none.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
            section.getChildren().add(none);
            return section;
        }

        // Per-scanner cards grid
        java.util.Map<ScannerType, List<Finding>> byScanner = all.stream()
            .collect(java.util.stream.Collectors.groupingBy(Finding::getSource));

        FlowPane scannerGrid = new FlowPane(16, 16);
        scannerGrid.setPrefWrapLength(Double.MAX_VALUE);

        byScanner.entrySet().stream()
            .sorted(java.util.Map.Entry.<ScannerType, List<Finding>>comparingByValue(
                java.util.Comparator.comparingInt(List::size)).reversed())
            .forEach(e -> scannerGrid.getChildren().add(buildScannerDetailCard(e.getKey(), e.getValue())));

        section.getChildren().add(scannerGrid);

        // Bottom row: top rules + top files
        HBox bottomRow = new HBox(16);
        bottomRow.getChildren().addAll(buildTopRulesPanel(all), buildTopFilesPanel(all));
        HBox.setHgrow(bottomRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(bottomRow.getChildren().get(1), Priority.ALWAYS);
        section.getChildren().add(bottomRow);

        return section;
    }

    private VBox buildScannerDetailCard(ScannerType scanner, List<Finding> findings) {
        String accent = scannerAccent(scanner.name());
        long total = findings.size();
        long errors   = findings.stream().filter(f -> f.getSeverity() == Severity.ERROR).count();
        long warnings = findings.stream().filter(f -> f.getSeverity() == Severity.WARNING).count();
        long infos    = findings.stream().filter(f -> f.getSeverity() == Severity.INFO).count();

        VBox card = new VBox(10);
        card.setPadding(new Insets(16, 20, 16, 20));
        card.setPrefWidth(260);
        card.setStyle("-fx-background-color: " + CARD_BG + "; -fx-background-radius: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");

        // Scanner name header
        HBox nameRow = new HBox(8);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Rectangle dot = new Rectangle(10, 10);
        dot.setFill(Color.web(accent));
        dot.setArcWidth(10); dot.setArcHeight(10);
        Label nameLabel = new Label(scanner.name());
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + accent + ";");
        Label totalLabel = new Label(total + " findings");
        totalLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        nameRow.getChildren().addAll(dot, nameLabel, sp, totalLabel);

        // Severity row
        HBox sevRow = new HBox(8);
        sevRow.getChildren().addAll(
            sevBadge("ERR",  errors,   RED),
            sevBadge("WARN", warnings, AMBER),
            sevBadge("INFO", infos,    GREEN)
        );

        // Severity progress bars
        VBox bars = new VBox(4);
        if (total > 0) {
            bars.getChildren().addAll(
                severityBar("Errors",   errors,   total, RED),
                severityBar("Warnings", warnings, total, AMBER),
                severityBar("Info",     infos,    total, GREEN)
            );
        }

        // Top 3 rules for this scanner
        VBox topRules = new VBox(4);
        Label rulesLabel = new Label("Top rules");
        rulesLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
        topRules.getChildren().add(rulesLabel);

        findings.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                f -> f.getRuleId() != null ? f.getRuleId() : "unknown",
                java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(3)
            .forEach(e -> {
                HBox ruleRow = new HBox(6);
                ruleRow.setAlignment(Pos.CENTER_LEFT);
                Label ruleId = new Label(truncate(e.getKey(), 28));
                ruleId.setStyle("-fx-font-size: 10px; -fx-text-fill: " + TEXT + "; -fx-font-family: 'Courier New';");
                Region rsp = new Region(); HBox.setHgrow(rsp, Priority.ALWAYS);
                Label cnt = new Label(String.valueOf(e.getValue()));
                cnt.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + ";");
                ruleRow.getChildren().addAll(ruleId, rsp, cnt);
                topRules.getChildren().add(ruleRow);
            });

        card.getChildren().addAll(nameRow, sevRow, bars, topRules);
        return card;
    }

    private HBox sevBadge(String label, long count, String color) {
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(3, 8, 3, 8));
        box.setStyle("-fx-background-color: " + color + "22; -fx-background-radius: 6;");
        Label lbl = new Label(label + " " + count);
        lbl.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        box.getChildren().add(lbl);
        return box;
    }

    private HBox severityBar(String label, long count, long total, String color) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + ";");
        lbl.setMinWidth(55);
        ProgressBar bar = new ProgressBar(total > 0 ? (double) count / total : 0);
        bar.setPrefWidth(Double.MAX_VALUE);
        bar.setPrefHeight(6);
        bar.setStyle("-fx-accent: " + color + "; -fx-background-color: " + SURFACE + ";");
        HBox.setHgrow(bar, Priority.ALWAYS);
        Label cnt = new Label(String.valueOf(count));
        cnt.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + ";");
        cnt.setMinWidth(28);
        row.getChildren().addAll(lbl, bar, cnt);
        return row;
    }

    private VBox buildTopRulesPanel(List<Finding> findings) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");

        Label title = new Label("Top Vulnerability Rules");
        title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
        box.getChildren().add(title);

        java.util.Map<String, Long> ruleCounts = findings.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                f -> f.getRuleId() != null ? f.getRuleId() : "unknown",
                java.util.stream.Collectors.counting()));

        long max = ruleCounts.values().stream().mapToLong(v -> v).max().orElse(1);

        ruleCounts.entrySet().stream()
            .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(8)
            .forEach(entry -> {
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);

                Label cat = new Label(truncate(entry.getKey(), 36));
                cat.setStyle("-fx-font-size: 11px; -fx-text-fill: " + TEXT
                    + "; -fx-font-family: 'Courier New';");
                cat.setMinWidth(200);

                ProgressBar bar = new ProgressBar((double) entry.getValue() / max);
                bar.setPrefWidth(Double.MAX_VALUE);
                bar.setPrefHeight(10);
                bar.setStyle("-fx-accent: " + ACCENT + ";");
                HBox.setHgrow(bar, Priority.ALWAYS);

                Label cnt = new Label(String.valueOf(entry.getValue()));
                cnt.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
                    + "; -fx-min-width: 36; -fx-alignment: center-right;");

                row.getChildren().addAll(cat, bar, cnt);
                box.getChildren().add(row);
            });

        return box;
    }

    private VBox buildTopFilesPanel(List<Finding> findings) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");

        Label title = new Label("Most Vulnerable Files");
        title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
        box.getChildren().add(title);

        java.util.Map<String, Long> fileCounts = findings.stream()
            .filter(f -> f.getFilePath() != null && !f.getFilePath().isBlank())
            .collect(java.util.stream.Collectors.groupingBy(
                f -> {
                    String p = f.getFilePath().replace('\\', '/');
                    int idx = p.lastIndexOf('/');
                    return idx >= 0 ? p.substring(idx + 1) + "  (" + p + ")" : p;
                },
                java.util.stream.Collectors.counting()));

        long max = fileCounts.values().stream().mapToLong(v -> v).max().orElse(1);

        fileCounts.entrySet().stream()
            .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(8)
            .forEach(entry -> {
                String key = entry.getKey();
                String fileName = key.contains("  (") ? key.substring(0, key.indexOf("  (")) : key;
                String fullPath = key.contains("  (") ? key.substring(key.indexOf("(") + 1, key.lastIndexOf(")")) : key;

                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);

                VBox fileInfo = new VBox(1);
                Label fName = new Label(fileName);
                fName.setStyle("-fx-font-size: 11px; -fx-text-fill: " + TEXT
                    + "; -fx-font-family: 'Courier New'; -fx-font-weight: bold;");
                Label fPath = new Label(truncate(fullPath, 40));
                fPath.setStyle("-fx-font-size: 9px; -fx-text-fill: " + MUTED + ";");
                fileInfo.getChildren().addAll(fName, fPath);
                fileInfo.setMinWidth(200);

                ProgressBar bar = new ProgressBar((double) entry.getValue() / max);
                bar.setPrefWidth(Double.MAX_VALUE);
                bar.setPrefHeight(10);
                bar.setStyle("-fx-accent: #EF4444;");
                HBox.setHgrow(bar, Priority.ALWAYS);

                Label cnt = new Label(String.valueOf(entry.getValue()));
                cnt.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
                    + "; -fx-min-width: 36; -fx-alignment: center-right;");

                row.getChildren().addAll(fileInfo, bar, cnt);
                box.getChildren().add(row);
            });

        return box;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String scannerAccent(String scanner) {
        return switch (scanner.toUpperCase()) {
            case "SEMGREP"    -> "#F97316";
            case "TRIVY"      -> "#0EA5E9";
            case "GITLEAKS"   -> "#EF4444";
            case "CODEQL"     -> "#8B5CF6";
            case "SONARQUBE"  -> "#0D9488";
            default           -> ACCENT;
        };
    }

    // ── RepoOption ────────────────────────────────────────────────────────

    public record RepoOption(long id, String name) {
        @Override public String toString() { return name; }
    }
}