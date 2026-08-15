package com.vulntriage.ui.triage;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.LlmResult;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Triage Results screen — read-only view of all LLM triage results in the DB.
 * Supports version/repo filtering, double-click detail view, and row deletion.
 */
public class TriageView {

    private static final java.time.format.DateTimeFormatter DATE_FMT =
        java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private static final String BG    = "#F1F5F9";
    private static final String CARD  = "#FFFFFF";
    private static final String TEXT  = "#111827";
    private static final String MUTED = "#6B7280";
    private static final String BLUE  = "#1D4ED8";
    private static final String GREEN = "#059669";
    private static final String RED   = "#DC2626";
    private static final String AMBER = "#D97706";

    private final AppContext ctx = AppContext.getInstance();

    private final ObservableList<ResultRow> resultRows    = FXCollections.observableArrayList();
    private final FilteredList<ResultRow>   filteredRows  = new FilteredList<>(resultRows, r -> true);
    private ComboBox<String>                repoFilter;
    private ComboBox<String>                versionFilter;

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");
        root.getChildren().addAll(buildHeader(), buildBody());

        // Load whatever is already in the DB
        loadExistingResults();

        // Refresh when any triage (including targeted) finishes
        ctx.triageRunningProperty().addListener((obs, wasRunning, isRunning) -> {
            if (!isRunning && wasRunning) {
                Platform.runLater(this::loadExistingResults);
            }
        });

        // Refresh when a workflow finishes
        ctx.workflowRunningProperty().addListener((obs, wasRunning, isRunning) -> {
            if (!isRunning && wasRunning) {
                Platform.runLater(this::loadExistingResults);
            }
        });

        return root;
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setPadding(new Insets(18, 28, 14, 28));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: #E5E7EB; -fx-border-width: 0 0 1 0;");

        VBox titleBlock = new VBox(2);
        Label title = new Label("Triage Results");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label("All LLM triage results across all runs and prompt versions.");
        sub.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        titleBlock.getChildren().addAll(title, sub);
        header.getChildren().add(titleBlock);
        return header;
    }

    // ── Body ───────────────────────────────────────────────────────────────

    private Node buildBody() {
        VBox resultsPanel = buildResultsPanel();
        VBox.setVgrow(resultsPanel, Priority.ALWAYS);
        return resultsPanel;
    }

    // ── Results panel (right) ──────────────────────────────────────────────

    private VBox buildResultsPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 28, 20, 20));
        panel.setStyle("-fx-background-color: " + BG + ";");

        HBox headingRow = new HBox(12);
        headingRow.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Results");
        heading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        Label hint = new Label("Double-click for full details  ·  Select + Delete to remove");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        repoFilter = new ComboBox<>();
        repoFilter.setPrefWidth(200);
        repoFilter.getItems().add("All Repositories");
        repoFilter.getSelectionModel().selectFirst();
        repoFilter.setStyle("-fx-font-size: 12px;");
        repoFilter.setOnAction(e -> applyFilters());

        versionFilter = new ComboBox<>();
        versionFilter.getItems().add("All Versions");
        versionFilter.getSelectionModel().selectFirst();
        versionFilter.setPrefWidth(120);
        versionFilter.setStyle("-fx-font-size: 12px;");
        versionFilter.setOnAction(e -> applyFilters());

        Button refreshResultsBtn = secondaryBtn("↻  Refresh");
        refreshResultsBtn.setOnAction(e -> loadExistingResults());

        Label selectionLabel = new Label();
        selectionLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + BLUE + "; -fx-background-color: #EFF6FF; "
            + "-fx-background-radius: 5; -fx-padding: 4 10;");
        selectionLabel.setVisible(false);
        selectionLabel.setManaged(false);

        headingRow.getChildren().addAll(heading, hint, spacer, selectionLabel, versionFilter, repoFilter, refreshResultsBtn);

        TableView<ResultRow> table = new TableView<>(filteredRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setStyle("-fx-background-color: white; -fx-background-radius: 8px;");
        table.setPlaceholder(new Label("No triage results yet. Run the triage to see results."));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(table, Priority.ALWAYS);

        table.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<ResultRow>) c -> {
                int n = table.getSelectionModel().getSelectedItems().size();
                selectionLabel.setVisible(n > 0);
                selectionLabel.setManaged(n > 0);
                selectionLabel.setText(n + " selected");
            });

        table.setRowFactory(tv -> {
            TableRow<ResultRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) showTriageDetail(row.getItem());
            });
            return row;
        });

        TableColumn<ResultRow, String> versionCol   = col("Version",    "promptVersion",  75);
        TableColumn<ResultRow, String> modelCol     = col("Model",      "modelUsed",     135);
        TableColumn<ResultRow, String> verdictCol   = col("Verdict",    "verdict",        75);
        TableColumn<ResultRow, String> confCol      = col("Confidence", "confidence",    100);
        TableColumn<ResultRow, String> triagedCol   = col("Triaged",    "triagedAt",     110);
        TableColumn<ResultRow, String> repoCol      = col("Repository", "repoName",      140);
        TableColumn<ResultRow, String> fileCol      = col("File",       "filePath",      155);
        TableColumn<ResultRow, String> ruleCol      = col("Rule",       "ruleId",        155);
        TableColumn<ResultRow, String> reasonCol    = col("Reasoning",  "reasoning",       0);
        reasonCol.setMaxWidth(350);

        versionCol.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle("v2.0".equals(item)
                    ? "-fx-text-fill: #7C3AED; -fx-font-weight: bold;"
                    : "-fx-text-fill: " + BLUE + "; -fx-font-weight: bold;");
            }
        });

        verdictCol.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle(switch (item) {
                    case "TP" -> "-fx-text-fill: " + GREEN + "; -fx-font-weight: bold;";
                    case "FP" -> "-fx-text-fill: " + RED   + "; -fx-font-weight: bold;";
                    default   -> "-fx-text-fill: " + AMBER + "; -fx-font-weight: bold;";
                });
            }
        });

        table.getColumns().addAll(versionCol, modelCol, verdictCol, confCol, triagedCol, repoCol, fileCol, ruleCol, reasonCol);

        // Delete key — removes selected rows from table AND from DB
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE) {
                List<ResultRow> selected = new ArrayList<>(
                    table.getSelectionModel().getSelectedItems());
                if (selected.isEmpty()) return;

                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + selected.size() + " triage result(s)? "
                    + "This removes them from the database and they will be re-triaged next run.",
                    ButtonType.YES, ButtonType.NO);
                confirm.setTitle("Delete Results");
                confirm.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.YES) {
                        selected.forEach(row -> {
                            ctx.llmRepo().deleteByFindingId(row.getFindingId());
                            resultRows.remove(row);
                        });
                    }
                });
            }
        });

        panel.getChildren().addAll(headingRow, table);
        return panel;
    }

    // ── Filters ────────────────────────────────────────────────────────────

    private void applyFilters() {
        String repo = repoFilter    != null ? repoFilter.getValue()    : null;
        String ver  = versionFilter != null ? versionFilter.getValue() : null;
        filteredRows.setPredicate(r ->
            (repo == null || "All Repositories".equals(repo) || repo.equals(r.getRepoName())) &&
            (ver  == null || "All Versions".equals(ver)      || ver.equals(r.getPromptVersion()))
        );
    }

    // ── Detail dialog ──────────────────────────────────────────────────────

    private void showTriageDetail(ResultRow row) {
        com.vulntriage.domain.Finding finding =
            ctx.findingRepo().findById(row.getFindingId()).orElse(null);
        com.vulntriage.domain.LlmResult llm =
            ctx.llmRepo().findByFindingId(row.getFindingId()).orElse(null);

        if (finding == null || llm == null) return;

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Triage Detail");
        dialog.setHeaderText(
            (finding.getRuleId() != null ? finding.getRuleId() : "—")
            + "  ·  "
            + (finding.getFilePath() != null ? finding.getFilePath() : "—"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(660);

        VBox content = new VBox(14);
        content.setPadding(new Insets(16));

        // ── Metadata row ───────────────────────────────────────────────────
        HBox meta = new HBox(24);
        meta.setAlignment(Pos.CENTER_LEFT);
        meta.getChildren().addAll(
            detailChip("Severity",       finding.getSeverity() != null ? finding.getSeverity().name() : "—"),
            detailChip("Category",       finding.getCategory() != null ? finding.getCategory() : "—"),
            detailChip("Line",           finding.getLineNumber() != null ? String.valueOf(finding.getLineNumber()) : "—"),
            detailChip("LLM Verdict",    llm.getLlmVerdict() != null ? llm.getLlmVerdict().name() : "—"),
            detailChip("Confidence",     llm.getConfidence() + "%"),
            detailChip("Model",          llm.getModelUsed() != null ? llm.getModelUsed() : "—"),
            detailChip("Prompt Version", llm.getPromptVersion() != null ? llm.getPromptVersion() : "—")
        );

        // ── Message ────────────────────────────────────────────────────────
        Label msgHead = sectionLabel("Scanner Message");
        Label msgText = new Label(finding.getMessage() != null ? finding.getMessage() : "");
        msgText.setWrapText(true);
        msgText.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT + ";");

        // ── Code snippet ───────────────────────────────────────────────────
        Label codeHead = sectionLabel("Code Snippet");
        TextArea codeArea = new TextArea(
            finding.getCodeSnippet() != null && !finding.getCodeSnippet().isBlank()
                ? finding.getCodeSnippet() : "(no snippet)");
        codeArea.setEditable(false);
        codeArea.setWrapText(false);
        codeArea.setPrefHeight(110);
        codeArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 11px; "
            + "-fx-background-color: #1E1E2E; -fx-text-fill: #CDD6F4; "
            + "-fx-control-inner-background: #1E1E2E;");

        // ── Reasoning ──────────────────────────────────────────────────────
        Label reasonHead = sectionLabel("LLM Reasoning");
        TextArea reasonArea = new TextArea(llm.getReasoning() != null ? llm.getReasoning() : "");
        reasonArea.setEditable(false);
        reasonArea.setWrapText(true);
        reasonArea.setPrefHeight(120);
        reasonArea.setStyle("-fx-font-size: 12px;");

        // ── Remediation ────────────────────────────────────────────────────
        Label remHead = sectionLabel("Remediation");
        TextArea remArea = new TextArea(llm.getRemediation() != null ? llm.getRemediation() : "");
        remArea.setEditable(false);
        remArea.setWrapText(true);
        remArea.setPrefHeight(90);
        remArea.setStyle("-fx-font-size: 12px;");

        content.getChildren().addAll(
            meta, new Separator(),
            msgHead, msgText,
            codeHead, codeArea,
            reasonHead, reasonArea,
            remHead, remArea
        );

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(620);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        dialog.getDialogPane().setContent(scroll);
        dialog.showAndWait();
    }

    private VBox detailChip(String label, String value) {
        VBox box = new VBox(2);
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + "; -fx-font-weight: bold;");
        Label val = new Label(value);
        val.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT + ";");
        box.getChildren().addAll(lbl, val);
        return box;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        return l;
    }

    // ── Load existing results from DB ──────────────────────────────────────

    private void loadExistingResults() {
        // Build finding lookup maps once (repo names, file paths, rule IDs)
        java.util.Map<Long, com.vulntriage.domain.Finding> findingById = new java.util.HashMap<>();
        java.util.Map<Long, String> repoByFindingId = new java.util.HashMap<>();
        java.util.Set<String> repoNames = new java.util.LinkedHashSet<>();

        ctx.repositoryRepo().findAll().forEach(repo -> {
            String repoName = repo.getName() != null ? repo.getName() : "Unknown";
            repoNames.add(repoName);
            ctx.findingRepo().findByRepositoryId(repo.getId()).forEach(f -> {
                findingById.put(f.getId(), f);
                repoByFindingId.put(f.getId(), repoName);
            });
        });

        // Load all LLM results across every prompt version so the table stays complete
        // regardless of how many prompt versions exist (v1.0, v2.0, v3.0, …).
        List<com.vulntriage.domain.LlmResult> allResults = ctx.llmRepo().findAll();

        List<ResultRow> loaded = new ArrayList<>();
        for (com.vulntriage.domain.LlmResult llm : allResults) {
            com.vulntriage.domain.Finding f = findingById.get(llm.getFindingId());
            if (f == null) continue; // orphaned result — finding was deleted
            String reasoning = llm.getReasoning() != null
                ? llm.getReasoning().substring(0, Math.min(120, llm.getReasoning().length())) : "";
            String triagedAt = llm.getCreatedAt() != null
                ? llm.getCreatedAt().format(DATE_FMT) : "—";
            loaded.add(new ResultRow(
                f.getId(),
                llm.getLlmVerdict() != null ? llm.getLlmVerdict().name() : "—",
                llm.getConfidence() + "%",
                repoByFindingId.getOrDefault(f.getId(), ""),
                f.getFilePath() != null ? f.getFilePath() : "",
                f.getRuleId()   != null ? f.getRuleId()   : "",
                reasoning,
                triagedAt,
                llm.getPromptVersion() != null ? llm.getPromptVersion() : "v1.0",
                llm.getModelUsed() != null ? llm.getModelUsed() : ""
            ));
        }

        resultRows.setAll(loaded);

        // Rebuild repo filter options, preserving the current selection
        if (repoFilter != null) {
            String currentSelection = repoFilter.getValue();
            repoFilter.getItems().setAll("All Repositories");
            repoFilter.getItems().addAll(repoNames);
            if (currentSelection != null && repoFilter.getItems().contains(currentSelection)) {
                repoFilter.setValue(currentSelection);
            } else {
                repoFilter.getSelectionModel().selectFirst();
            }
        }

        // Rebuild version filter from versions actually present in results
        if (versionFilter != null) {
            String currentVer = versionFilter.getValue();
            java.util.List<String> versions = loaded.stream()
                .map(ResultRow::getPromptVersion)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
            // Disable listener during rebuild to prevent mid-setAll onAction firing
            versionFilter.setOnAction(null);
            versionFilter.getItems().setAll("All Versions");
            versionFilter.getItems().addAll(versions);
            if (currentVer != null && !currentVer.equals("All Versions")
                    && versionFilter.getItems().contains(currentVer))
                versionFilter.setValue(currentVer);
            else
                versionFilter.getSelectionModel().selectFirst();
            versionFilter.setOnAction(e -> applyFilters());
        }

    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void styleField(TextField f) {
        f.setStyle("-fx-font-size: 12px; -fx-border-color: #E5E7EB; "
            + "-fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 5 8;");
    }

    private Label sectionHeading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #9CA3AF;");
        return l;
    }

    private Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #374151;");
        return l;
    }

    private Button secondaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: " + BLUE + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 14; "
            + "-fx-border-color: #BFDBFE; -fx-border-radius: 6;");
        return b;
    }

    private <T> TableColumn<ResultRow, T> col(String title, String prop, int w) {
        TableColumn<ResultRow, T> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(prop));
        if (w > 0) col.setPrefWidth(w);
        return col;
    }

    // ── Row model ──────────────────────────────────────────────────────────

    public static class ResultRow {
        private final long   findingId;
        private final String verdict;
        private final String confidence;
        private final String repoName;
        private final String filePath;
        private final String ruleId;
        private final String reasoning;
        private final String triagedAt;
        private final String promptVersion;
        private final String modelUsed;

        public ResultRow(long findingId, String verdict, String confidence,
                         String repoName, String filePath, String ruleId,
                         String reasoning, String triagedAt, String promptVersion,
                         String modelUsed) {
            this.findingId     = findingId;
            this.verdict       = verdict;
            this.confidence    = confidence;
            this.repoName      = repoName;
            this.filePath      = filePath;
            this.ruleId        = ruleId;
            this.reasoning     = reasoning;
            this.triagedAt     = triagedAt;
            this.promptVersion = promptVersion;
            this.modelUsed     = modelUsed;
        }

        public long   getFindingId()     { return findingId; }
        public String getVerdict()       { return verdict; }
        public String getConfidence()    { return confidence; }
        public String getRepoName()      { return repoName; }
        public String getFilePath()      { return filePath; }
        public String getRuleId()        { return ruleId; }
        public String getReasoning()     { return reasoning; }
        public String getTriagedAt()     { return triagedAt; }
        public String getPromptVersion() { return promptVersion; }
        public String getModelUsed()     { return modelUsed; }
    }
}
