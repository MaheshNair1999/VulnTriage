package com.vulntriage.ui.triage;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.EvaluationRun;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.api.TriageStrategy;
import com.vulntriage.triage.ollama.OllamaTriageStrategy;
import com.vulntriage.triage.ollama.PromptBuilder;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
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
 * LLM Triage screen.
 *
 * Runs the configured LLM over all findings and saves results to the DB.
 * Supports stop, crash-resume (skips already-triaged findings), and
 * row deletion via the Delete key.
 */
public class TriageView {

    private static final String BG    = "#F1F5F9";
    private static final String CARD  = "#FFFFFF";
    private static final String TEXT  = "#111827";
    private static final String MUTED = "#6B7280";
    private static final String BLUE  = "#1D4ED8";
    private static final String GREEN = "#059669";
    private static final String RED   = "#DC2626";
    private static final String AMBER = "#D97706";

    private final AppContext ctx = AppContext.getInstance();

    private Label        statusLabel;
    private ProgressBar  progressBar;
    private Label        progressLabel;
    private Button       runBtn;
    private Button       stopBtn;
    private TextField    runNameField;
    private TextField    ollamaUrlField;
    private TextField    ollamaModelField;
    private Label        connectionStatus;
    private Task<Void>   currentTask;   // kept so stopBtn can cancel it

    private final ObservableList<ResultRow> resultRows    = FXCollections.observableArrayList();
    private final FilteredList<ResultRow>   filteredRows  = new FilteredList<>(resultRows, r -> true);
    private ComboBox<String>                repoFilter;

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");
        root.getChildren().addAll(buildHeader(), buildBody());

        // Load whatever is already in the DB
        loadExistingResults();

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
        Label title = new Label("LLM Triage");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label("Run AI-assisted triage on findings using Ollama.");
        sub.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        titleBlock.getChildren().addAll(title, sub);
        header.getChildren().add(titleBlock);
        return header;
    }

    // ── Body ───────────────────────────────────────────────────────────────

    private HBox buildBody() {
        HBox body = new HBox(0);
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox configPanel  = buildConfigPanel();
        VBox resultsPanel = buildResultsPanel();
        HBox.setHgrow(resultsPanel, Priority.ALWAYS);
        body.getChildren().addAll(configPanel, resultsPanel);
        return body;
    }

    // ── Config panel (left) ────────────────────────────────────────────────

    private VBox buildConfigPanel() {
        VBox panel = new VBox(16);
        panel.setPrefWidth(300);
        panel.setMinWidth(260);
        panel.setPadding(new Insets(24, 20, 24, 28));
        panel.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: #E5E7EB; -fx-border-width: 0 1 0 0;");

        Label connHeading = sectionHeading("Ollama Connection");

        ollamaUrlField = new TextField(ctx.getOllamaUrl());
        ollamaUrlField.setPromptText("http://localhost:11434");
        styleField(ollamaUrlField);

        ollamaModelField = new TextField(ctx.getOllamaModel());
        ollamaModelField.setPromptText("qwen3:8b");
        styleField(ollamaModelField);

        connectionStatus = new Label("Using: " + ctx.triageStrategy().getModelName());
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + GREEN
            + "; -fx-font-weight: bold;");

        Button checkBtn = secondaryBtn("Check Connection");
        checkBtn.setMaxWidth(Double.MAX_VALUE);
        checkBtn.setOnAction(e -> checkConnection());

        Label runHeading = sectionHeading("Triage Run");

        runNameField = new TextField("Evaluation Run " + LocalDateTime.now().toLocalDate());
        styleField(runNameField);

        long findingCount = countAllFindings();
        Label sampleInfo = new Label(findingCount + " findings available\n(all will be triaged)");
        sampleInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        sampleInfo.setWrapText(true);

        // ── Run + Stop buttons side by side ───────────────────────────────
        runBtn = primaryBtn("▶  Run LLM Triage");
        runBtn.setMaxWidth(Double.MAX_VALUE);
        runBtn.setPrefHeight(44);
        runBtn.setOnAction(e -> runTriage());

        stopBtn = new Button("⏹  Stop");
        stopBtn.setMaxWidth(Double.MAX_VALUE);
        stopBtn.setPrefHeight(44);
        stopBtn.setDisable(true);
        stopBtn.setStyle("-fx-background-color: #FEF2F2; -fx-text-fill: " + RED + "; "
            + "-fx-background-radius: 6; -fx-font-size: 13px; -fx-font-weight: bold; "
            + "-fx-border-color: #FECACA; -fx-border-radius: 6;");
        stopBtn.setOnAction(e -> stopTriage());

        HBox btnRow = new HBox(8, runBtn, stopBtn);
        HBox.setHgrow(runBtn,  Priority.ALWAYS);
        HBox.setHgrow(stopBtn, Priority.SOMETIMES);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-accent: " + BLUE + ";");

        progressLabel = new Label("");
        progressLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        progressLabel.setWrapText(true);

        statusLabel = new Label("Configure Ollama and click Run.");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        statusLabel.setWrapText(true);

        panel.getChildren().addAll(
            connHeading,
            fieldLabel("Ollama URL"), ollamaUrlField,
            fieldLabel("Model"), ollamaModelField,
            connectionStatus, checkBtn,
            new Separator(),
            runHeading,
            fieldLabel("Run Name"), runNameField,
            sampleInfo,
            new Separator(),
            btnRow, progressBar, progressLabel, statusLabel
        );
        return panel;
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
        repoFilter.setOnAction(e -> {
            String selected = repoFilter.getValue();
            if (selected == null || selected.equals("All Repositories")) {
                filteredRows.setPredicate(r -> true);
            } else {
                filteredRows.setPredicate(r -> selected.equals(r.getRepoName()));
            }
        });

        Button refreshResultsBtn = secondaryBtn("↻  Refresh");
        refreshResultsBtn.setOnAction(e -> loadExistingResults());

        Label selectionLabel = new Label();
        selectionLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + BLUE + "; -fx-background-color: #EFF6FF; "
            + "-fx-background-radius: 5; -fx-padding: 4 10;");
        selectionLabel.setVisible(false);
        selectionLabel.setManaged(false);

        headingRow.getChildren().addAll(heading, hint, spacer, selectionLabel, repoFilter, refreshResultsBtn);

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

        TableColumn<ResultRow, String> verdictCol = col("Verdict",    "verdict",    80);
        TableColumn<ResultRow, String> confCol    = col("Confidence", "confidence", 80);
        TableColumn<ResultRow, String> repoCol    = col("Repository", "repoName",   160);
        TableColumn<ResultRow, String> fileCol    = col("File",       "filePath",   180);
        TableColumn<ResultRow, String> ruleCol    = col("Rule",       "ruleId",     200);
        TableColumn<ResultRow, String> reasonCol  = col("Reasoning",  "reasoning",  0);

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

        table.getColumns().addAll(verdictCol, confCol, repoCol, fileCol, ruleCol, reasonCol);

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

    // ── Stop ───────────────────────────────────────────────────────────────

    private void stopTriage() {
        if (currentTask != null && currentTask.isRunning()) {
            currentTask.cancel(true);
            ctx.setTriageRunning(false);
            setRunningState(false, "Triage stopped. Results so far are saved — click Run to resume.");
        }
    }

    // ── Check connection ───────────────────────────────────────────────────

    private void checkConnection() {
        String url   = ollamaUrlField.getText().trim();
        String model = ollamaModelField.getText().trim();

        connectionStatus.setText("Checking…");
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");

        Task<Boolean> task = new Task<>() {
            @Override protected Boolean call() {
                return new OllamaTriageStrategy(url, model).isAvailable();
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            boolean ok = task.getValue();
            if (ok) {
                connectionStatus.setText("✓ Connected — " + model + " ready");
                connectionStatus.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; "
                    + "-fx-text-fill: " + GREEN + ";");
                ctx.enableOllama(url, model);
            } else {
                connectionStatus.setText("✗ Cannot reach Ollama at " + url
                    + "\nMake sure Ollama is running: ollama serve");
                connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
            }
        }));
        task.setOnFailed(e -> Platform.runLater(() -> {
            connectionStatus.setText("✗ Error: " + task.getException().getMessage());
            connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
        }));
        new Thread(task, "ollama-check").start();
    }

    // ── Run triage ─────────────────────────────────────────────────────────

    private void runTriage() {
        String url   = ollamaUrlField.getText().trim();
        String model = ollamaModelField.getText().trim();
        String name  = runNameField.getText().trim();

        if (name.isBlank()) { setStatus("Please enter a run name."); return; }

        List<Finding> allFindings = new ArrayList<>();
        java.util.Map<Long, String> findingRepoName = new java.util.HashMap<>();
        ctx.repositoryRepo().findAll().forEach(repo -> {
            String rn = repo.getName() != null ? repo.getName() : "Unknown";
            List<Finding> repoFindings = ctx.findingRepo().findByRepositoryId(repo.getId());
            repoFindings.forEach(f -> findingRepoName.put(f.getId(), rn));
            allFindings.addAll(repoFindings);
        });

        if (allFindings.isEmpty()) {
            setStatus("No findings found. Run a scan first.");
            return;
        }

        List<Finding> sample = new ArrayList<>(allFindings);

        EvaluationRun run = new EvaluationRun(name,
            "LLM triage using " + model + " on " + sample.size() + " findings");
        ctx.evalRepo().save(run);
        sample.forEach(f -> ctx.evalRepo().saveSampleFinding(run.getId(), f.getId()));

        TriageStrategy strategy = (!url.isBlank() && !model.isBlank())
            ? new OllamaTriageStrategy(url, model)
            : ctx.triageStrategy();

        long alreadyDone = sample.stream()
            .filter(f -> ctx.llmRepo().existsByFindingId(f.getId())).count();
        String startMsg = alreadyDone > 0
            ? "Resuming — " + alreadyDone + " already triaged, "
                + (sample.size() - alreadyDone) + " remaining…"
            : "Starting triage on " + sample.size() + " findings…";

        resultRows.clear();
        setRunningState(true, startMsg);
        ctx.setTriageRunning(true);

        currentTask = new Task<>() {
            @Override
            protected Void call() {
                int total   = sample.size();
                int processed = 0;
                int delayMs = com.vulntriage.config.AppConfig.getInstance().getTriageDelayMs();

                for (Finding finding : sample) {
                    if (isCancelled()) break;

                    // Crash resume — skip and display existing result
                    if (ctx.llmRepo().existsByFindingId(finding.getId())) {
                        ctx.llmRepo().findByFindingId(finding.getId()).ifPresent(existing -> {
                            ResultRow row = new ResultRow(
                                finding.getId(),
                                existing.getLlmVerdict().name(),
                                existing.getConfidence() + "%",
                                findingRepoName.getOrDefault(finding.getId(), ""),
                                finding.getFilePath() != null ? finding.getFilePath() : "",
                                finding.getRuleId()   != null ? finding.getRuleId()   : "",
                                existing.getReasoning() != null
                                    ? existing.getReasoning().substring(0,
                                        Math.min(120, existing.getReasoning().length())) : ""
                            );
                            Platform.runLater(() -> resultRows.add(row));
                        });
                        processed++;
                        final int done = processed;
                        Platform.runLater(() ->
                            progressLabel.setText(done + " / " + total + " (resuming…)"));
                        continue;
                    }

                    // ── Inference — no UI work ────────────────────────────
                    ResultRow row = null;
                    try {
                        TriageResult result = strategy.triage(finding);

                        LlmResult llmResult = new LlmResult();
                        llmResult.setFindingId      (finding.getId());
                        llmResult.setEvaluationRunId(run.getId());
                        llmResult.setLlmVerdict     (result.getVerdict());
                        llmResult.setConfidence     (result.getConfidence());
                        llmResult.setReasoning      (result.getReasoning());
                        llmResult.setRemediation    (result.getRemediation());
                        llmResult.setModelUsed      (strategy.getModelName());
                        llmResult.setPromptVersion  (PromptBuilder.PROMPT_VERSION);
                        llmResult.setCreatedAt      (LocalDateTime.now());
                        ctx.llmRepo().save(llmResult);

                        row = new ResultRow(
                            finding.getId(),
                            result.getVerdict().name(),
                            result.getConfidence() + "%",
                            findingRepoName.getOrDefault(finding.getId(), ""),
                            finding.getFilePath() != null ? finding.getFilePath() : "",
                            finding.getRuleId()   != null ? finding.getRuleId()   : "",
                            result.getReasoning() != null
                                ? result.getReasoning().substring(0,
                                    Math.min(120, result.getReasoning().length())) : ""
                        );
                    } catch (Exception e) {
                        updateMessage("Warning: failed to triage finding "
                            + finding.getId() + ": " + e.getMessage());
                    }

                    processed++;
                    final int done     = processed;
                    final ResultRow fr = row;

                    // ── Delay — UI updates mid-delay when GPU is idle ─────
                    if (delayMs > 0 && processed < total) {
                        try {
                            Thread.sleep(delayMs / 2);
                            Platform.runLater(() -> {
                                if (fr != null) resultRows.add(0, fr);
                                progressBar.setProgress((double) done / total);
                                progressLabel.setText(done + " / " + total + " findings processed");
                            });
                            Thread.sleep(delayMs / 2);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        Platform.runLater(() -> {
                            if (fr != null) resultRows.add(0, fr);
                            progressBar.setProgress((double) done / total);
                            progressLabel.setText(done + " / " + total + " findings processed");
                        });
                    }

                    // Extra cooldown every 50 findings
                    if (processed % 50 == 0 && processed < total) {
                        Platform.runLater(() ->
                            progressLabel.setText(done + " / " + total + " — cooling down 10s…"));
                        try { Thread.sleep(10_000); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                return null;
            }
        };

        currentTask.setOnSucceeded(e -> Platform.runLater(() -> {
            ctx.setTriageRunning(false);
            setRunningState(false, "Triage complete. Go to Evaluate to see metrics.");
        }));
        currentTask.setOnFailed(e -> Platform.runLater(() -> {
            ctx.setTriageRunning(false);
            setRunningState(false, "Triage failed: " + currentTask.getException().getMessage());
        }));
        currentTask.setOnCancelled(e -> Platform.runLater(() -> {
            ctx.setTriageRunning(false);
            setRunningState(false, "Stopped. Results saved — click Run to resume.");
        }));

        Thread t = new Thread(currentTask, "triage-thread");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
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
            detailChip("Severity",   finding.getSeverity() != null ? finding.getSeverity().name() : "—"),
            detailChip("Category",   finding.getCategory() != null ? finding.getCategory() : "—"),
            detailChip("Line",       finding.getLineNumber() != null ? String.valueOf(finding.getLineNumber()) : "—"),
            detailChip("LLM Verdict", llm.getLlmVerdict() != null ? llm.getLlmVerdict().name() : "—"),
            detailChip("Confidence", llm.getConfidence() + "%"),
            detailChip("Model",      llm.getModelUsed() != null ? llm.getModelUsed() : "—")
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
        if (currentTask != null && currentTask.isRunning()) return; // don't overwrite live run

        List<ResultRow> loaded = new ArrayList<>();
        java.util.Set<String> repoNames = new java.util.LinkedHashSet<>();

        ctx.repositoryRepo().findAll().forEach(repo -> {
            String repoName = repo.getName() != null ? repo.getName() : "Unknown";
            repoNames.add(repoName);
            ctx.findingRepo().findByRepositoryId(repo.getId()).forEach(f -> {
                ctx.llmRepo().findByFindingId(f.getId()).ifPresent(llm -> {
                    String reasoning = llm.getReasoning() != null
                        ? llm.getReasoning().substring(0, Math.min(120, llm.getReasoning().length()))
                        : "";
                    loaded.add(new ResultRow(
                        f.getId(),
                        llm.getLlmVerdict() != null ? llm.getLlmVerdict().name() : "—",
                        llm.getConfidence() + "%",
                        repoName,
                        f.getFilePath() != null ? f.getFilePath() : "",
                        f.getRuleId()   != null ? f.getRuleId()   : "",
                        reasoning
                    ));
                });
            });
        });

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

        if (!loaded.isEmpty()) {
            setStatus(loaded.size() + " findings triaged — run again to re-triage, or delete rows to re-do individual ones.");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private long countAllFindings() {
        return ctx.repositoryRepo().findAll().stream()
            .mapToLong(r -> ctx.findingRepo().findByRepositoryId(r.getId()).size()).sum();
    }

    private void setRunningState(boolean running, String message) {
        runBtn.setDisable(running);
        stopBtn.setDisable(!running);
        progressBar.setVisible(running || progressBar.getProgress() > 0);
        setStatus(message);
    }

    private void setStatus(String msg) { statusLabel.setText(msg); }

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

    private Button primaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-size: 13px; -fx-font-weight: bold;");
        return b;
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

        public ResultRow(long findingId, String verdict, String confidence,
                         String repoName, String filePath, String ruleId, String reasoning) {
            this.findingId  = findingId;
            this.verdict    = verdict;
            this.confidence = confidence;
            this.repoName   = repoName;
            this.filePath   = filePath;
            this.ruleId     = ruleId;
            this.reasoning  = reasoning;
        }

        public long   getFindingId()  { return findingId; }
        public String getVerdict()    { return verdict; }
        public String getConfidence() { return confidence; }
        public String getRepoName()   { return repoName; }
        public String getFilePath()   { return filePath; }
        public String getRuleId()     { return ruleId; }
        public String getReasoning()  { return reasoning; }
    }
}
