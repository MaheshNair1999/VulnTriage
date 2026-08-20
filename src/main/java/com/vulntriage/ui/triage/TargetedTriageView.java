package com.vulntriage.ui.triage;

import com.vulntriage.app.AppContext;
import com.vulntriage.ui.UIUtils;
import com.vulntriage.domain.EvaluationRun;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.PromptTemplate;
import com.vulntriage.domain.Repository;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.api.TriageStrategy;
import com.vulntriage.triage.mock.MockTriageStrategy;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static com.vulntriage.config.ThemeColors.*;

/**
 * Targeted Triage screen.
 *
 * Lets the user pick filter criteria (scanner, repository, rule pattern, severity),
 * preview how many findings match, then run a second LLM triage pass on just
 * that subset — without touching the rest of the data.
 *
 * Results are saved to the database as a new EvaluationRun tagged with the
 * selected prompt version, so they appear immediately in the Evaluate screen.
 */
public class TargetedTriageView {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd MMM HH:mm");


    private final AppContext ctx = AppContext.getInstance();

    // ── Filter controls ────────────────────────────────────────────────────
    private ComboBox<String>        scannerBox;
    private ComboBox<String>        repoBox;
    private TextField               ruleField;
    private ComboBox<String>        severityBox;
    private Label                   matchLabel;

    // ── LLM connection status ──────────────────────────────────────────────
    private Label                   connectionStatus;

    // ── Triage config controls ─────────────────────────────────────────────
    private ComboBox<PromptTemplate> promptSelector;
    private TextField               runNameField;
    private TextField               reposPathField;
    private CheckBox                forceRetriageBox;

    // ── Run controls ───────────────────────────────────────────────────────
    private Button    runBtn;
    private Button    stopBtn;
    private ProgressBar progressBar;
    private Label     progressLabel;
    private Label     statusLabel;
    private Task<Void> currentTask;

    // ── Results / preview ──────────────────────────────────────────────────
    private final ObservableList<FindingRow>           previewRows = FXCollections.observableArrayList();
    private final ObservableList<TriageView.ResultRow> resultRows  = FXCollections.observableArrayList();
    private Label previewSelLabel;

    private VBox previewPanel;
    private VBox resultsPanel;
    private StackPane rightStack;
    private TableView<FindingRow> previewTable;

    // ── Cached matched findings (set on preview, used on run) ─────────────
    private List<Finding>       matchedFindings   = List.of();
    private Map<Long, String>   findingRepoName   = Map.of();
    private Map<Long, String>   repoLocalPathById = Map.of();

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");
        root.getChildren().addAll(buildHeader(), buildBody());
        return root;
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setPadding(new Insets(18, 28, 14, 28));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 1 0;");

        VBox titleBlock = new VBox(2);
        Label title = new Label("Triage");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label(
            "Filter findings by criteria and run an LLM triage pass on the matched subset.");
        sub.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        titleBlock.getChildren().addAll(title, sub);
        header.getChildren().add(titleBlock);
        return header;
    }

    // ── Body ───────────────────────────────────────────────────────────────

    private HBox buildBody() {
        HBox body = new HBox(0);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox left  = buildConfigPanel();
        Node right = buildRightPanel();
        HBox.setHgrow(right, Priority.ALWAYS);
        body.getChildren().addAll(left, right);
        return body;
    }

    // ── Left config panel ──────────────────────────────────────────────────

    private VBox buildConfigPanel() {
        VBox panel = new VBox(14);
        panel.setPrefWidth(300);
        panel.setMinWidth(260);
        panel.setPadding(new Insets(24, 20, 24, 28));
        panel.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 1 0 0;");

        // ── LLM backend ────────────────────────────────────────────────────
        Label connHeading = sectionHeading("LLM Backend");

        Label providerLabel = new Label(ctx.triageStrategy().getModelName());
        providerLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT
            + "; -fx-background-color: " + SURFACE + "; -fx-padding: 6 10; "
            + "-fx-background-radius: 5; -fx-border-color: " + BORDER + "; -fx-border-radius: 5;");
        providerLabel.setMaxWidth(Double.MAX_VALUE);

        connectionStatus = new Label("Using: " + ctx.triageStrategy().getModelName());
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + GREEN + "; -fx-font-weight: bold;");

        Label settingsHint = new Label("Change provider in Settings.");
        settingsHint.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + ";");

        Button checkBtn = secondaryBtn("Check Connection");
        checkBtn.setMaxWidth(Double.MAX_VALUE);
        checkBtn.setOnAction(e -> checkConnection());

        // ── Filter criteria ────────────────────────────────────────────────
        Label filterHead = sectionHeading("Filter Criteria");

        scannerBox = new ComboBox<>(FXCollections.observableArrayList(
            "All Scanners", "SEMGREP", "TRIVY", "GITLEAKS", "CODEQL", "SONARQUBE"));
        scannerBox.setValue("All Scanners");
        scannerBox.setMaxWidth(Double.MAX_VALUE);
        scannerBox.setStyle("-fx-font-size: 12px;");

        repoBox = new ComboBox<>();
        repoBox.setMaxWidth(Double.MAX_VALUE);
        repoBox.setStyle("-fx-font-size: 12px;");
        loadRepoOptions();

        ruleField = new TextField();
        ruleField.setPromptText("e.g. xss, sql, template  (empty = all)");
        styleField(ruleField);

        severityBox = new ComboBox<>(FXCollections.observableArrayList(
            "All Severities", "ERROR", "WARNING", "INFO", "ERROR + WARNING"));
        severityBox.setValue("All Severities");
        severityBox.setMaxWidth(Double.MAX_VALUE);
        severityBox.setStyle("-fx-font-size: 12px;");

        Button previewBtn = new Button("◎  Preview Matches");
        previewBtn.setMaxWidth(Double.MAX_VALUE);
        previewBtn.setStyle("-fx-background-color: " + TEAL + "; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-font-weight: bold; "
            + "-fx-padding: 8 14;");
        previewBtn.setOnAction(e -> previewMatches());

        matchLabel = new Label("Click Preview to see matching findings.");
        matchLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        matchLabel.setWrapText(true);

        // ── Triage config ──────────────────────────────────────────────────
        Label triageHead = sectionHeading("Triage Config");

        promptSelector = new ComboBox<>();
        promptSelector.setMaxWidth(Double.MAX_VALUE);
        promptSelector.setStyle("-fx-font-size: 12px;");
        promptSelector.setOnShowing(e -> reloadPrompts());
        reloadPrompts();

        runNameField = new TextField("Targeted Run " + LocalDateTime.now().toLocalDate());
        styleField(runNameField);

        reposPathField = new TextField();
        reposPathField.setPromptText("e.g. C:\\Projects  (for {{source_context}})");
        styleField(reposPathField);

        Label reposNote = new Label("Optional — only needed if the prompt uses {{source_context}}.");
        reposNote.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + ";");
        reposNote.setWrapText(true);

        forceRetriageBox = new CheckBox("Force re-triage (overwrite existing results)");
        forceRetriageBox.setSelected(false);
        forceRetriageBox.setStyle("-fx-font-size: 11px; -fx-text-fill: " + TEXT + ";");

        // ── Run / Stop ─────────────────────────────────────────────────────
        runBtn = new Button("▶  Run Triage");
        runBtn.setMaxWidth(Double.MAX_VALUE);
        runBtn.setPrefHeight(44);
        runBtn.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-size: 13px; -fx-font-weight: bold;");
        runBtn.setOnAction(e -> runTriage());

        stopBtn = new Button("⏹  Stop");
        stopBtn.setMaxWidth(Double.MAX_VALUE);
        stopBtn.setPrefHeight(44);
        stopBtn.setDisable(true);
        stopBtn.setStyle("-fx-background-color: " + RED_LIGHT_BG + "; -fx-text-fill: " + RED + "; "
            + "-fx-background-radius: 6; -fx-font-size: 13px; -fx-font-weight: bold; "
            + "-fx-border-color: " + RED_BORDER + "; -fx-border-radius: 6;");
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

        statusLabel = new Label("Set filter criteria, preview matches, then run.");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        statusLabel.setWrapText(true);

        panel.getChildren().addAll(
            connHeading,
            providerLabel, settingsHint,
            connectionStatus, checkBtn,
            new Separator(),
            filterHead,
            fieldLabel("Scanner"),  scannerBox,
            fieldLabel("Repository"), repoBox,
            fieldLabel("Rule contains"), ruleField,
            fieldLabel("Severity"), severityBox,
            previewBtn, matchLabel,
            new Separator(),
            triageHead,
            fieldLabel("Prompt Template"), promptSelector,
            fieldLabel("Run Name"), runNameField,
            fieldLabel("Repos Base Path"), reposPathField, reposNote,
            forceRetriageBox,
            new Separator(),
            btnRow, progressBar, progressLabel, statusLabel
        );

        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.setPrefWidth(300);
        scroll.setMinWidth(260);
        scroll.setStyle("-fx-background-color: " + CARD
            + "; -fx-background: " + CARD + "; -fx-border-color: " + BORDER + "; -fx-border-width: 0 1 0 0;");

        // Wrap in a VBox to preserve VGrow
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        wrapper.setPrefWidth(300);
        wrapper.setMinWidth(260);
        wrapper.setStyle("-fx-background-color: " + CARD + ";");
        return wrapper;
    }

    // ── Right panel (preview / results) ────────────────────────────────────

    private Node buildRightPanel() {
        previewPanel  = buildPreviewTable();
        resultsPanel  = buildResultsTable();

        rightStack = new StackPane(previewPanel, resultsPanel);
        resultsPanel.setVisible(false);

        VBox wrapper = new VBox(rightStack);
        VBox.setVgrow(rightStack, Priority.ALWAYS);
        return wrapper;
    }

    private VBox buildPreviewTable() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 28, 20, 20));
        panel.setStyle("-fx-background-color: " + BG + ";");

        // ── Heading row with selection badge ──────────────────────────────
        HBox headingRow = new HBox(12);
        headingRow.setAlignment(Pos.CENTER_LEFT);

        Label heading = new Label("Matched Findings");
        heading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        Label hint = new Label("Double-click for details  ·  Ctrl/Shift to multi-select");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        previewSelLabel = new Label();
        previewSelLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + TEAL + "; -fx-background-color: " + TEAL_BG + "; "
            + "-fx-background-radius: 5; -fx-padding: 4 10;");
        previewSelLabel.setVisible(false);
        previewSelLabel.setManaged(false);

        headingRow.getChildren().addAll(heading, hint, spacer, previewSelLabel);

        // ── Table ──────────────────────────────────────────────────────────
        previewTable = new TableView<>(previewRows);
        TableView<FindingRow> table = previewTable;
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 8px;");
        table.setPlaceholder(new Label("No findings matched yet."));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(table, Priority.ALWAYS);

        // Selection count badge
        table.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<FindingRow>) c -> {
                int n = table.getSelectionModel().getSelectedItems().size();
                previewSelLabel.setVisible(n > 0);
                previewSelLabel.setManaged(n > 0);
                previewSelLabel.setText(n + " selected");
            });

        // Double-click → detail dialog (handled at table level to preserve multi-select)
        table.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getClickCount() == 2) {
                FindingRow selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) showFindingDetail(selected);
            }
        });

        // Ctrl+A → select all (TableViewBehavior only fires when table already has focus)
        table.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.A) {
                table.getSelectionModel().selectAll();
                e.consume();
            }
        });

        TableColumn<FindingRow, String> repoCol   = previewCol("Repository", "repoName",  130);
        TableColumn<FindingRow, String> sourceCol = previewCol("Scanner",    "source",     90);
        TableColumn<FindingRow, String> sevCol    = previewCol("Severity",   "severity",   80);
        TableColumn<FindingRow, String> ruleCol   = previewCol("Rule ID",    "ruleId",      0);
        TableColumn<FindingRow, String> fileCol   = previewCol("File",       "filePath",  180);

        sevCol.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle(switch (item) {
                    case "ERROR"   -> "-fx-text-fill: " + RED   + "; -fx-font-weight: bold;";
                    case "WARNING" -> "-fx-text-fill: " + AMBER + "; -fx-font-weight: bold;";
                    default        -> "-fx-text-fill: " + MUTED + ";";
                });
            }
        });

        table.getColumns().addAll(repoCol, sourceCol, sevCol, ruleCol, fileCol);

        panel.getChildren().addAll(headingRow, table);
        return panel;
    }

    private VBox buildResultsTable() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(20, 28, 20, 20));
        panel.setStyle("-fx-background-color: " + BG + ";");

        HBox headingRow = new HBox(12);
        headingRow.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Triage Results");
        heading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label hint = new Label("Double-click a row for full reasoning.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        Label resultSelLabel = new Label();
        resultSelLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + TEAL + "; -fx-background-color: " + TEAL_BG + "; "
            + "-fx-background-radius: 5; -fx-padding: 4 10;");
        resultSelLabel.setVisible(false);
        resultSelLabel.setManaged(false);
        headingRow.getChildren().addAll(heading, hint, headSpacer, resultSelLabel);

        TableView<TriageView.ResultRow> table = new TableView<>(resultRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 8px;");
        table.setPlaceholder(new Label("Triage in progress…"));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(table, Priority.ALWAYS);

        table.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.A) {
                table.getSelectionModel().selectAll();
                e.consume();
            }
        });

        table.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<TriageView.ResultRow>) c -> {
                int n = table.getSelectionModel().getSelectedItems().size();
                resultSelLabel.setVisible(n > 0);
                resultSelLabel.setManaged(n > 0);
                resultSelLabel.setText(n + " selected");
            });

        table.setRowFactory(tv -> {
            TableRow<TriageView.ResultRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) showDetail(row.getItem());
            });
            return row;
        });

        TableColumn<TriageView.ResultRow, String> verdictCol  = resultCol("Verdict",    "verdict",       75);
        TableColumn<TriageView.ResultRow, String> confCol     = resultCol("Confidence", "confidence",    90);
        TableColumn<TriageView.ResultRow, String> versionCol  = resultCol("Version",    "promptVersion", 65);
        TableColumn<TriageView.ResultRow, String> repoCol     = resultCol("Repository", "repoName",     130);
        TableColumn<TriageView.ResultRow, String> fileCol     = resultCol("File",       "filePath",     160);
        TableColumn<TriageView.ResultRow, String> ruleCol     = resultCol("Rule",       "ruleId",       150);
        TableColumn<TriageView.ResultRow, String> reasonCol   = resultCol("Reasoning",  "reasoning",      0);
        reasonCol.setMaxWidth(340);

        verdictCol.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
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

        versionCol.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle("-fx-text-fill: " + TEAL + "; -fx-font-weight: bold;");
            }
        });

        table.getColumns().addAll(verdictCol, confCol, versionCol, repoCol, fileCol, ruleCol, reasonCol);

        panel.getChildren().addAll(headingRow, table);
        return panel;
    }

    // ── Preview matches ────────────────────────────────────────────────────

    private void previewMatches() {
        Map<Long, String> repoNameMap  = new HashMap<>();
        Map<Long, String> repoPathMap  = new HashMap<>();
        List<Finding> all = new ArrayList<>();

        ctx.repositoryRepo().findAll().forEach(r -> {
            String rn = r.getName() != null ? r.getName() : "Unknown";
            String rp = r.getLocalPath() != null ? r.getLocalPath() : "";
            repoNameMap.put(r.getId(), rn);
            repoPathMap.put(r.getId(), rp);
            all.addAll(ctx.findingRepo().findByRepositoryId(r.getId()));
        });

        List<Finding> matched = applyFilters(all, repoNameMap);

        // Cache for the triage run
        matchedFindings   = matched;
        findingRepoName   = repoNameMap;
        repoLocalPathById = repoPathMap;

        // Update preview table
        List<FindingRow> rows = matched.stream()
            .map(f -> new FindingRow(
                f.getId(),
                repoNameMap.getOrDefault(f.getRepositoryId(), ""),
                f.getSource()     != null ? f.getSource().name()     : "—",
                f.getSeverity()   != null ? f.getSeverity().name()   : "—",
                f.getRuleId()     != null ? f.getRuleId()             : "—",
                f.getFilePath()   != null ? f.getFilePath()           : "—"))
            .collect(Collectors.toList());

        Platform.runLater(() -> {
            previewRows.setAll(rows);
            showPreviewPanel();
            String countTxt = matched.size() + " finding" + (matched.size() == 1 ? "" : "s") + " match";
            matchLabel.setText(countTxt + ".  Click Run to triage them.");
            matchLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: "
                + (matched.isEmpty() ? RED : GREEN) + "; -fx-font-weight: bold;");
        });
    }

    // ── Run triage ─────────────────────────────────────────────────────────

    private void runTriage() {
        if (matchedFindings.isEmpty()) {
            setStatus("Run Preview first — no findings matched.");
            return;
        }

        PromptTemplate tmpl = promptSelector.getSelectionModel().getSelectedItem();
        if (tmpl == null) { setStatus("Select a prompt template."); return; }

        String runName = runNameField.getText().trim();
        if (runName.isBlank()) { setStatus("Enter a run name."); return; }

        final String templateStr   = tmpl.getTemplate();
        final String promptVersion = tmpl.getVersion();
        final boolean needsContext = templateStr.contains("{{source_context}}");
        final String  reposBase    = reposPathField.getText().trim();
        final boolean forceRetriage = forceRetriageBox.isSelected();

        List<Finding> sample = new ArrayList<>(matchedFindings);
        final List<Finding> finalSample = sample;

        final String finalRunName = runName;
        EvaluationRun existingRun = (!forceRetriage)
            ? ctx.evalRepo().findAll().stream()
                .filter(r -> finalRunName.equals(r.getName()))
                .findFirst()
                .orElse(null)
            : null;
        if (existingRun == null) {
            EvaluationRun newRun = new EvaluationRun(runName,
                "Targeted triage — prompt " + promptVersion + " on " + sample.size() + " findings");
            ctx.evalRepo().save(newRun);
            finalSample.forEach(f -> ctx.evalRepo().saveSampleFinding(newRun.getId(), f.getId()));
            existingRun = newRun;
        }
        final EvaluationRun run = existingRun;

        TriageStrategy strategy = resolveStrategy();

        long alreadyDone = forceRetriage ? 0 : finalSample.stream()
            .filter(f -> ctx.llmRepo().findByFindingIdAndPromptVersion(f.getId(), promptVersion).isPresent())
            .count();
        String startMsg = !forceRetriage && alreadyDone > 0
            ? "Resuming — " + alreadyDone + " already triaged with " + promptVersion + ", "
                + (finalSample.size() - alreadyDone) + " remaining…"
            : "Starting targeted triage on " + finalSample.size() + " findings…";

        resultRows.clear();
        showResultsPanel();
        setRunningState(true, startMsg);
        ctx.setTriageRunning(true);

        final Map<Long, String> repoNameSnap = new HashMap<>(findingRepoName);
        final Map<Long, String> repoPathSnap = new HashMap<>(repoLocalPathById);
        final int delayMs = com.vulntriage.config.AppConfig.getInstance().getTriageDelayMs();

        currentTask = new Task<>() {
            @Override
            protected Void call() {
                int total = finalSample.size(), processed = 0;

                for (Finding finding : finalSample) {
                    if (isCancelled()) break;

                    // Force re-triage: wipe same-version results so we save fresh (other versions untouched)
                    if (forceRetriage) ctx.llmRepo().deleteByFindingIdAndPromptVersion(finding.getId(), promptVersion);

                    // Crash resume — skip only if a result for this exact prompt version already exists
                    if (!forceRetriage) {
                        java.util.Optional<LlmResult> sameVer = ctx.llmRepo()
                            .findByFindingIdAndPromptVersion(finding.getId(), promptVersion);
                        if (sameVer.isPresent()) {
                            LlmResult existing = sameVer.get();
                            TriageView.ResultRow row = new TriageView.ResultRow(
                                finding.getId(),
                                existing.getLlmVerdict().name(),
                                existing.getConfidence() + "%",
                                repoNameSnap.getOrDefault(finding.getRepositoryId(), ""),
                                finding.getFilePath() != null ? finding.getFilePath() : "",
                                finding.getRuleId()   != null ? finding.getRuleId()   : "",
                                existing.getReasoning() != null
                                    ? existing.getReasoning().substring(0,
                                        Math.min(120, existing.getReasoning().length())) : "",
                                existing.getCreatedAt() != null
                                    ? existing.getCreatedAt().format(DATE_FMT) : "—",
                                existing.getPromptVersion() != null ? existing.getPromptVersion() : promptVersion,
                                existing.getModelUsed() != null ? existing.getModelUsed() : ""
                            );
                            Platform.runLater(() -> resultRows.add(row));
                            processed++;
                            final int done = processed;
                            Platform.runLater(() ->
                                progressLabel.setText(done + " / " + total + " (resuming…)"));
                            continue;
                        }
                    }

                    // Source context
                    String sourceCtx = null;
                    if (needsContext && finding.getFilePath() != null) {
                        String base = !reposBase.isBlank()
                            ? reposBase
                            : repoPathSnap.getOrDefault(finding.getRepositoryId(), "");
                        if (!base.isBlank()) sourceCtx = readSourceContext(base, finding);
                    }

                    TriageView.ResultRow row = null;
                    try {
                        TriageResult result = strategy.triageWithTemplate(
                            finding, templateStr, promptVersion, sourceCtx);

                        LlmResult llmResult = new LlmResult();
                        llmResult.setFindingId      (finding.getId());
                        llmResult.setEvaluationRunId(run.getId());
                        llmResult.setLlmVerdict     (result.getVerdict());
                        llmResult.setConfidence     (result.getConfidence());
                        llmResult.setReasoning      (result.getReasoning());
                        llmResult.setRemediation    (result.getRemediation());
                        llmResult.setModelUsed      (strategy.getModelName());
                        llmResult.setPromptVersion  (promptVersion);
                        llmResult.setCreatedAt      (LocalDateTime.now());
                        ctx.llmRepo().save(llmResult);

                        String shortReason = result.getReasoning() != null
                            ? result.getReasoning().substring(
                                0, Math.min(120, result.getReasoning().length())) : "";
                        row = new TriageView.ResultRow(
                            finding.getId(),
                            result.getVerdict().name(),
                            result.getConfidence() + "%",
                            repoNameSnap.getOrDefault(finding.getRepositoryId(), ""),
                            finding.getFilePath() != null ? finding.getFilePath() : "",
                            finding.getRuleId()   != null ? finding.getRuleId()   : "",
                            shortReason,
                            LocalDateTime.now().format(DATE_FMT),
                            promptVersion,
                            strategy.getModelName()
                        );
                    } catch (Exception e) {
                        updateMessage("Warning: failed on finding " + finding.getId() + ": " + e.getMessage());
                    }

                    processed++;
                    final int done     = processed;
                    final TriageView.ResultRow fr = row;

                    if (delayMs > 0 && processed < total) {
                        try {
                            Thread.sleep(delayMs / 2);
                            Platform.runLater(() -> {
                                if (fr != null) resultRows.add(0, fr);
                                progressBar.setProgress((double) done / total);
                                progressLabel.setText(done + " / " + total);
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
                            progressLabel.setText(done + " / " + total);
                        });
                    }

                    if (processed % 50 == 0 && processed < total) {
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
            setRunningState(false, "Done. " + resultRows.size() + " findings triaged — check Evaluate for metrics.");
        }));
        currentTask.setOnFailed(e -> Platform.runLater(() -> {
            ctx.setTriageRunning(false);
            setRunningState(false, "Failed: " + currentTask.getException().getMessage());
        }));
        currentTask.setOnCancelled(e -> Platform.runLater(() -> {
            ctx.setTriageRunning(false);
            setRunningState(false, "Stopped. Results saved so far.");
        }));

        Thread t = new Thread(currentTask, "targeted-triage-thread");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<Finding> applyFilters(List<Finding> all, Map<Long, String> repoNameMap) {
        String scanner  = scannerBox.getValue();
        String repoSel  = repoBox.getValue();
        String rule     = ruleField.getText().trim().toLowerCase();
        String severity = severityBox.getValue();

        return all.stream().filter(f -> {
            // Scanner
            if (!"All Scanners".equals(scanner)) {
                if (f.getSource() == null
                        || !f.getSource().name().equalsIgnoreCase(scanner)) return false;
            }
            // Repository
            if (!"All Repositories".equals(repoSel) && repoSel != null) {
                String rname = repoNameMap.getOrDefault(f.getRepositoryId(), "");
                if (!repoSel.equals(rname)) return false;
            }
            // Rule pattern
            if (!rule.isBlank()) {
                if (f.getRuleId() == null
                        || !f.getRuleId().toLowerCase().contains(rule)) return false;
            }
            // Severity
            if (!"All Severities".equals(severity) && severity != null) {
                if (f.getSeverity() == null) return false;
                String sev = f.getSeverity().name();
                if ("ERROR + WARNING".equals(severity)) {
                    if (!"ERROR".equals(sev) && !"WARNING".equals(sev)) return false;
                } else {
                    if (!severity.equals(sev)) return false;
                }
            }
            return true;
        }).collect(Collectors.toList());
    }

    private TriageStrategy resolveStrategy() {
        return ctx.triageStrategy();
    }

    private void stopTriage() {
        if (currentTask != null && currentTask.isRunning()) {
            currentTask.cancel(true);
            ctx.setTriageRunning(false);
            setRunningState(false, "Stopped — results saved so far.");
        }
    }

    private void showPreviewPanel() {
        previewPanel.setVisible(true);
        resultsPanel.setVisible(false);
        Platform.runLater(() -> { if (previewTable != null) previewTable.requestFocus(); });
    }

    private void showResultsPanel() {
        previewPanel.setVisible(false);
        resultsPanel.setVisible(true);
    }

    private void setRunningState(boolean running, String message) {
        runBtn.setDisable(running);
        stopBtn.setDisable(!running);
        progressBar.setVisible(running || progressBar.getProgress() > 0);
        setStatus(message);
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    private void loadRepoOptions() {
        List<String> options = new ArrayList<>();
        options.add("All Repositories");
        ctx.repositoryRepo().findAll().stream()
            .map(r -> r.getName() != null ? r.getName() : "")
            .filter(n -> !n.isBlank())
            .forEach(options::add);
        repoBox.setItems(FXCollections.observableArrayList(options));
        repoBox.getSelectionModel().selectFirst();
    }

    private void reloadPrompts() {
        PromptTemplate current = promptSelector.getSelectionModel().getSelectedItem();
        String currentVersion  = current != null ? current.getVersion() : null;

        List<PromptTemplate> prompts = ctx.promptTemplateRepo().findAll();
        promptSelector.getItems().setAll(prompts);

        promptSelector.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(PromptTemplate t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : t.getName() + "  (" + t.getVersion() + ")");
            }
        });
        promptSelector.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(PromptTemplate t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? "Select prompt…" : t.getName() + "  (" + t.getVersion() + ")");
            }
        });

        if (currentVersion != null) {
            prompts.stream().filter(p -> currentVersion.equals(p.getVersion()))
                .findFirst().ifPresent(p -> promptSelector.getSelectionModel().select(p));
        }
        if (promptSelector.getSelectionModel().isEmpty() && !prompts.isEmpty()) {
            promptSelector.getSelectionModel().selectFirst();
        }
    }

    /**
     * Reads 30 header lines + 80 before + 80 after the flagged line.
     * Mirrors the context window used in the v2.0 experiment.
     */
    private String readSourceContext(String reposBase, Finding finding) {
        try {
            String relPath = finding.getFilePath();
            if (relPath == null) return null;
            String clean = relPath.replaceAll("^[/\\\\]+", "");
            java.nio.file.Path candidate = java.nio.file.Paths.get(reposBase, clean);
            if (!java.nio.file.Files.exists(candidate)) {
                String[] parts = clean.replace('\\', '/').split("/", 2);
                if (parts.length == 2)
                    candidate = java.nio.file.Paths.get(reposBase, parts[1]);
            }
            if (!java.nio.file.Files.exists(candidate)) return null;

            List<String> lines = java.nio.file.Files.readAllLines(candidate);
            int flagged = finding.getLineNumber() != null ? finding.getLineNumber() - 1 : 0;
            int total   = lines.size();

            StringBuilder sb = new StringBuilder();
            int headerEnd = Math.min(30, total);
            for (int i = 0; i < headerEnd; i++)
                sb.append(i + 1).append("  ").append(lines.get(i)).append('\n');

            if (flagged >= headerEnd) sb.append("...\n");

            int before = Math.max(headerEnd, flagged - 80);
            for (int i = before; i < flagged && i < total; i++)
                sb.append(i + 1).append("  ").append(lines.get(i)).append('\n');

            if (flagged >= 0 && flagged < total)
                sb.append(">>> ").append(flagged + 1).append("  ").append(lines.get(flagged)).append('\n');

            int after = Math.min(total, flagged + 81);
            for (int i = flagged + 1; i < after; i++)
                sb.append(i + 1).append("  ").append(lines.get(i)).append('\n');

            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void showDetail(TriageView.ResultRow row) {
        Finding f   = ctx.findingRepo().findById(row.getFindingId()).orElse(null);
        LlmResult l = ctx.llmRepo().findByFindingId(row.getFindingId()).orElse(null);
        if (f == null || l == null) return;

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Triage Detail");
        UIUtils.applyTheme(dialog);
        dialog.setHeaderText(
            (f.getRuleId()   != null ? f.getRuleId()   : "—") + "  ·  "
            + (f.getFilePath() != null ? f.getFilePath() : "—"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(660);

        VBox content = new VBox(14);
        content.setPadding(new Insets(16));

        Label reasonHead = new Label("LLM Reasoning");
        reasonHead.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        TextArea reasonArea = new TextArea(l.getReasoning() != null ? l.getReasoning() : "");
        reasonArea.setEditable(false);
        reasonArea.setWrapText(true);
        reasonArea.setPrefHeight(140);

        Label remHead = new Label("Remediation");
        remHead.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        TextArea remArea = new TextArea(l.getRemediation() != null ? l.getRemediation() : "");
        remArea.setEditable(false);
        remArea.setWrapText(true);
        remArea.setPrefHeight(100);

        Label codeHead = new Label("Code Snippet");
        codeHead.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        TextArea codeArea = new TextArea(
            f.getCodeSnippet() != null && !f.getCodeSnippet().isBlank() ? f.getCodeSnippet() : "(no snippet)");
        codeArea.setEditable(false);
        codeArea.setWrapText(false);
        codeArea.setPrefHeight(100);
        codeArea.getStyleClass().add("code-snippet");
        codeArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 11px; -fx-text-fill: #CDD6F4;");

        content.getChildren().addAll(reasonHead, reasonArea, remHead, remArea, codeHead, codeArea);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(540);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        dialog.getDialogPane().setContent(scroll);
        UIUtils.fixCodeSnippetBackground(dialog, codeArea);
        dialog.showAndWait();
    }

    // ── Finding detail (preview row double-click) ──────────────────────────

    private void showFindingDetail(FindingRow row) {
        Finding f = ctx.findingRepo().findById(row.getFindingId()).orElse(null);
        if (f == null) return;

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Finding Detail");
        UIUtils.applyTheme(dialog);
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(740);
        dialog.getDialogPane().setPrefHeight(580);

        VBox content = new VBox(14);
        content.setPadding(new Insets(16));

        // Metadata chips
        HBox chips = new HBox(10);
        chips.setAlignment(Pos.CENTER_LEFT);
        chips.getChildren().addAll(
            detailChip("Scanner",  row.getSource()),
            detailChip("Severity", row.getSeverity()),
            detailChip("Line",     f.getLineNumber() != null ? String.valueOf(f.getLineNumber()) : "—"),
            detailChip("CWE",      f.getCwe()        != null ? f.getCwe()        : "—"),
            detailChip("Category", f.getCategory()   != null ? f.getCategory()   : "—")
        );

        Label ruleLabel = new Label(row.getRuleId());
        ruleLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label pathLabel = new Label(f.getFilePath() != null ? f.getFilePath() : "");
        pathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        pathLabel.setWrapText(true);

        Label msgHead = new Label("Message");
        msgHead.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
        TextArea msgArea = new TextArea(f.getMessage() != null ? f.getMessage() : "(no message)");
        msgArea.setEditable(false);
        msgArea.setWrapText(true);
        msgArea.setPrefHeight(80);

        // Code snippet — double-click to open full file
        Label snippetHint = new Label("⤢ double-click to view full file");
        snippetHint.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        HBox snippetHeader = new HBox(8, new Label("Code Snippet"), snippetHint);
        ((Label) snippetHeader.getChildren().get(0))
            .setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
        snippetHeader.setAlignment(Pos.CENTER_LEFT);

        String snippet = f.getCodeSnippet() != null && !f.getCodeSnippet().isBlank()
            ? f.getCodeSnippet() : "(no snippet)";
        TextArea snippetArea = new TextArea(snippet);
        snippetArea.setEditable(false);
        snippetArea.setWrapText(false);
        snippetArea.setPrefHeight(180);
        snippetArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 11px; "
            + "-fx-background-color: #070B14; -fx-text-fill: #CDD6F4; "
            + "-fx-control-inner-background: #070B14; -fx-border-color: #334155; "
            + "-fx-border-radius: 6; -fx-background-radius: 6;");
        snippetArea.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) showFullFileDialog(f);
        });

        content.getChildren().addAll(chips, ruleLabel, pathLabel,
            msgHead, msgArea, snippetHeader, snippetArea);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        dialog.getDialogPane().setContent(scroll);
        UIUtils.fixCodeSnippetBackground(dialog, snippetArea);
        dialog.showAndWait();
    }

    private void showFullFileDialog(Finding f) {
        String fileText;
        String resolvedPath = "(unknown)";
        try {
            String relPath = f.getFilePath();
            if (relPath == null) throw new IllegalArgumentException("no file path");

            String clean = relPath.replaceAll("^[/\\\\]+", "");
            String repoBase = repoLocalPathById.getOrDefault(f.getRepositoryId(), "");

            java.nio.file.Path candidate = null;
            if (!repoBase.isBlank()) {
                String[] parts = clean.replace('\\', '/').split("/", 2);
                candidate = java.nio.file.Paths.get(repoBase, clean);
                if (!java.nio.file.Files.exists(candidate) && parts.length == 2)
                    candidate = java.nio.file.Paths.get(repoBase, parts[1]);
                if (!java.nio.file.Files.exists(candidate)) candidate = null;
            }
            if (candidate == null) throw new java.io.FileNotFoundException("not found on disk");

            resolvedPath = candidate.toString();
            List<String> lines = java.nio.file.Files.readAllLines(candidate);
            int flagged = f.getLineNumber() != null ? f.getLineNumber() - 1 : -1;

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                String prefix = (i == flagged) ? ">>> " : "    ";
                sb.append(String.format("%s%4d  %s%n", prefix, i + 1, lines.get(i)));
            }
            fileText = sb.toString();
        } catch (Exception ex) {
            fileText = "(could not read file: " + ex.getMessage() + ")";
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Full File");
        UIUtils.applyTheme(dialog);
        dialog.setHeaderText(resolvedPath);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(860);
        dialog.getDialogPane().setPrefHeight(660);

        final String finalText = fileText;
        TextArea fileArea = new TextArea(finalText);
        fileArea.setEditable(false);
        fileArea.setWrapText(false);
        fileArea.getStyleClass().add("code-snippet");
        fileArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 12px; -fx-text-fill: #CDD6F4;");

        Button copyBtn = new Button("Copy All");
        copyBtn.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 6 16;");
        copyBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(finalText);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("Copied ✓");
        });

        HBox toolbar = new HBox(copyBtn);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.setPadding(new Insets(0, 0, 6, 0));

        VBox body = new VBox(6, toolbar, fileArea);
        body.setPadding(new Insets(12));
        VBox.setVgrow(fileArea, Priority.ALWAYS);

        // Scroll to flagged line after layout
        if (f.getLineNumber() != null && f.getLineNumber() > 0) {
            int target = f.getLineNumber() - 1;
            fileArea.sceneProperty().addListener((obs, o, scene) -> {
                if (scene != null) {
                    Platform.runLater(() -> {
                        String[] ls = finalText.split("\n", -1);
                        int pos = 0;
                        for (int i = 0; i < Math.min(target, ls.length); i++) pos += ls[i].length() + 1;
                        fileArea.positionCaret(pos);
                        fileArea.setScrollTop(Double.MAX_VALUE);
                    });
                }
            });
        }

        dialog.getDialogPane().setContent(body);
        UIUtils.fixCodeSnippetBackground(dialog, fileArea);
        dialog.showAndWait();
    }

    private VBox detailChip(String label, String value) {
        Label lbl = new Label(label.toUpperCase());
        lbl.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
        Label val = new Label(value != null ? value : "—");
        val.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        VBox chip = new VBox(1, lbl, val);
        chip.setPadding(new Insets(6, 10, 6, 10));
        chip.setStyle("-fx-background-color: " + SURFACE2 + "; -fx-background-radius: 6; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6;");
        return chip;
    }

    // ── Column factories ───────────────────────────────────────────────────

    private <T> TableColumn<FindingRow, T> previewCol(String title, String prop, int w) {
        TableColumn<FindingRow, T> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(prop));
        if (w > 0) col.setPrefWidth(w);
        return col;
    }

    private <T> TableColumn<TriageView.ResultRow, T> resultCol(String title, String prop, int w) {
        TableColumn<TriageView.ResultRow, T> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(prop));
        if (w > 0) col.setPrefWidth(w);
        return col;
    }

    // ── Ollama connection ──────────────────────────────────────────────────

    private void checkConnection() {
        TriageStrategy strategy = ctx.triageStrategy();
        connectionStatus.setText("Checking " + strategy.getModelName() + "…");
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");

        javafx.concurrent.Task<Boolean> task = new javafx.concurrent.Task<>() {
            @Override protected Boolean call() { return strategy.isAvailable(); }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            if (task.getValue()) {
                connectionStatus.setText("✓ Connected — " + strategy.getModelName() + " ready");
                connectionStatus.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; "
                    + "-fx-text-fill: " + GREEN + ";");
            } else {
                connectionStatus.setText("✗ Cannot reach " + strategy.getModelName()
                    + ". Check Settings.");
                connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
            }
        }));
        task.setOnFailed(e -> Platform.runLater(() -> {
            connectionStatus.setText("✗ Error: " + task.getException().getMessage());
            connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
        }));
        new Thread(task, "llm-check").start();
    }

    // ── Style helpers ──────────────────────────────────────────────────────

    private Button secondaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + BTN_BG_SECONDARY + "; -fx-text-fill: " + BLUE + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 14; "
            + "-fx-border-color: " + BTN_BORDER_SECONDARY + "; -fx-border-radius: 6;");
        return b;
    }

    private void styleField(TextField f) {
        f.setStyle("-fx-font-size: 12px; -fx-border-color: " + BORDER + "; "
            + "-fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 5 8;");
    }

    private Label sectionHeading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #9CA3AF;");
        return l;
    }

    private Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + DIM + ";");
        return l;
    }

    // ── Preview row model ──────────────────────────────────────────────────

    public static class FindingRow {
        private final long   findingId;
        private final String repoName;
        private final String source;
        private final String severity;
        private final String ruleId;
        private final String filePath;

        public FindingRow(long findingId, String repoName, String source,
                          String severity, String ruleId, String filePath) {
            this.findingId = findingId;
            this.repoName  = repoName;
            this.source    = source;
            this.severity  = severity;
            this.ruleId    = ruleId;
            this.filePath  = filePath;
        }

        public long   getFindingId() { return findingId; }
        public String getRepoName()  { return repoName; }
        public String getSource()    { return source; }
        public String getSeverity()  { return severity; }
        public String getRuleId()    { return ruleId; }
        public String getFilePath()  { return filePath; }
    }
}