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
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.List;

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

    private static final String BG      = "#F1F5F9";
    private static final String CARD_BG = "#FFFFFF";
    private static final String ACCENT  = "#1D4ED8";
    private static final String GREEN   = "#059669";
    private static final String RED     = "#DC2626";
    private static final String AMBER   = "#D97706";
    private static final String TEXT    = "#111827";
    private static final String MUTED   = "#6B7280";

    private static final long ALL_REPOS = -1L;

    private final AppContext ctx = AppContext.getInstance();

    // The outer scroll pane — we rebuild its content on repo/scanner change
    private ScrollPane scroll;
    private long   selectedRepoId  = ALL_REPOS;
    private String selectedScanner = "All Scanners";

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
        chart.setStyle("-fx-background-color: white; -fx-background-radius: 10px; "
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
        card.setStyle("-fx-background-color: white; -fx-background-radius: 10px; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");
        card.setPadding(new Insets(16));
        card.getChildren().add(chart);
        return card;
    }

    private VBox wrapInCard(Node content) {
        VBox card = new VBox(0);
        card.setStyle("-fx-background-color: white; -fx-background-radius: 10px; "
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

    private static final java.util.List<String> SCANNER_OPTIONS = java.util.List.of(
        "All Scanners", "Semgrep", "Trivy", "Gitleaks", "CodeQL", "SonarQube"
    );

    private VBox buildScannerBreakdown() {
        VBox section = new VBox(16);

        // Header row: title + scanner dropdown
        HBox header = new HBox(16);
        header.setAlignment(Pos.CENTER_LEFT);

        Label heading = new Label("Scanner Breakdown");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ComboBox<String> scannerPicker = new ComboBox<>();
        scannerPicker.getItems().addAll(SCANNER_OPTIONS);
        scannerPicker.setValue(selectedScanner);
        scannerPicker.setStyle("-fx-font-size: 12px;");
        scannerPicker.setPrefWidth(160);
        scannerPicker.setOnAction(e -> {
            if (scannerPicker.getValue() != null) {
                selectedScanner = scannerPicker.getValue();
                rebuildContent();
            }
        });

        header.getChildren().addAll(heading, spacer, scannerPicker);

        // Filter findings by selected scanner
        List<Finding> filtered = scopedFindings().stream()
            .filter(f -> {
                if ("All Scanners".equals(selectedScanner)) return true;
                return f.getSource().name().equalsIgnoreCase(selectedScanner)
                    || f.getSource().name().equalsIgnoreCase(selectedScanner.replace(" ", ""));
            })
            .toList();

        if (filtered.isEmpty()) {
            Label none = new Label("No findings for " + selectedScanner + ". Run a scan first.");
            none.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
            section.getChildren().addAll(header, none);
            return section;
        }

        long errCount  = filtered.stream().filter(f -> f.getSeverity() == Severity.ERROR).count();
        long warnCount = filtered.stream().filter(f -> f.getSeverity() == Severity.WARNING).count();
        long infoCount = filtered.stream().filter(f -> f.getSeverity() == Severity.INFO).count();
        long total     = filtered.size();

        String accent = scannerAccent(selectedScanner);

        HBox cards = new HBox(16);
        cards.getChildren().addAll(
            scannerCard("ERROR",   errCount,  "#7F1D1D", "#FEF2F2", accent),
            scannerCard("WARNING", warnCount, "#92400E", "#FFFBEB", accent),
            scannerCard("INFO",    infoCount, "#166534", "#F0FDF4", accent),
            scannerCard("TOTAL",   total,     "#1E3A8A", "#EFF6FF", accent)
        );
        cards.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        // Top categories bar
        VBox categoryBar = buildCategoryBar(filtered, accent);

        section.getChildren().addAll(header, cards, categoryBar);
        return section;
    }

    private VBox buildCategoryBar(List<Finding> findings, String accent) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color: white; -fx-background-radius: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);");

        Label title = new Label("Top Categories");
        title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
        box.getChildren().add(title);

        // Count by category, take top 6
        java.util.Map<String, Long> categoryCounts = findings.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                f -> f.getCategory() != null ? f.getCategory() : "unknown",
                java.util.stream.Collectors.counting()));

        long max = categoryCounts.values().stream().mapToLong(v -> v).max().orElse(1);

        categoryCounts.entrySet().stream()
            .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(6)
            .forEach(entry -> {
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);

                Label cat = new Label(entry.getKey());
                cat.setStyle("-fx-font-size: 11px; -fx-text-fill: " + TEXT + ";");
                cat.setMinWidth(120);

                double pct = (double) entry.getValue() / max;
                ProgressBar bar = new ProgressBar(pct);
                bar.setPrefWidth(Double.MAX_VALUE);
                bar.setPrefHeight(12);
                bar.setStyle("-fx-accent: " + accent + ";");
                HBox.setHgrow(bar, Priority.ALWAYS);

                Label cnt = new Label(String.valueOf(entry.getValue()));
                cnt.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-min-width: 40; -fx-alignment: center-right;");

                row.getChildren().addAll(cat, bar, cnt);
                box.getChildren().add(row);
            });

        return box;
    }

    private VBox scannerCard(String label, long count, String textColor, String bgColor, String accent) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(16, 20, 16, 20));
        card.setStyle("-fx-background-color: " + bgColor + "; -fx-background-radius: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 8, 0, 0, 2);");

        Rectangle bar = new Rectangle(28, 3);
        bar.setFill(Color.web(accent));
        bar.setArcWidth(3); bar.setArcHeight(3);

        Label valueLabel = new Label(String.valueOf(count));
        valueLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + textColor + "; -fx-font-family: 'Courier New';");

        Label nameLabel = new Label(label);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + textColor + ";");

        card.getChildren().addAll(bar, valueLabel, nameLabel);
        return card;
    }

    private String scannerAccent(String scanner) {
        return switch (scanner) {
            case "Semgrep"    -> "#F97316";
            case "Trivy"      -> "#0EA5E9";
            case "Gitleaks"   -> "#EF4444";
            case "CodeQL"     -> "#8B5CF6";
            case "SonarQube"  -> "#0D9488";
            default           -> ACCENT;
        };
    }

    // ── RepoOption ────────────────────────────────────────────────────────

    public record RepoOption(long id, String name) {
        @Override public String toString() { return name; }
    }
}
