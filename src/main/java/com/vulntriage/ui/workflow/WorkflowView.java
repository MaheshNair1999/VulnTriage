package com.vulntriage.ui.workflow;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.PromptTemplate;
import com.vulntriage.domain.WorkflowDefinition;
import com.vulntriage.domain.WorkflowStep;
import com.vulntriage.domain.WorkflowStep.StepType;
import com.vulntriage.event.ProgressLogger;
import com.vulntriage.event.UiProgressUpdater;
import com.vulntriage.pipeline.WorkflowParser;
import com.vulntriage.pipeline.WorkflowParseException;
import com.vulntriage.pipeline.WorkflowStepFactory;
import com.vulntriage.pipeline.api.PipelineContext;
import com.vulntriage.pipeline.api.PipelineStage;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static com.vulntriage.config.ThemeColors.*;

/**
 * Workflow Designer screen — create, edit, save and run security workflows.
 *
 * Users define workflows by:
 *   1. Giving the workflow a name
 *   2. Adding steps from a palette (Scan, Filter, Sample, Triage, Score, Report)
 *   3. Configuring each step's parameters
 *   4. Saving the workflow to the database
 *   5. Running it against a registered repository
 *
 * The workflow is serialised as JSON and stored via WorkflowRepository.
 * On Run, WorkflowStepFactory instantiates each step and the
 * PipelineOrchestrator chains them for execution.
 */
public class WorkflowView {


    private final AppContext    ctx    = AppContext.getInstance();
    private final WorkflowParser parser = new WorkflowParser();

    // ── State ──────────────────────────────────────────────────────────────
    private final ObservableList<StepRow>            stepRows       = FXCollections.observableArrayList();
    private final ObservableList<WorkflowDefinition> savedWorkflows = FXCollections.observableArrayList();
    private boolean hasUnsavedChanges = false;

    // ── UI refs ────────────────────────────────────────────────────────────
    private TextField   nameField;
    private TextArea    jsonPreview;
    private Label       statusLabel;
    private ProgressBar progressBar;
    private ListView<WorkflowDefinition> savedList;
    private Button stopWorkflowBtn;
    private volatile Thread currentWorkflowThread;

    public Node build() {
        loadSaved();

        SplitPane split = new SplitPane(buildDesigner(), buildSavedPanel());
        split.setDividerPositions(0.65);
        split.setStyle("-fx-background-color: " + BG + ";");

        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");
        root.getChildren().addAll(buildHeader(), split);
        VBox.setVgrow(split, Priority.ALWAYS);

        // Dirty-state tracking
        stepRows.addListener((javafx.collections.ListChangeListener<StepRow>) c -> hasUnsavedChanges = true);
        nameField.textProperty().addListener((o, old, n) -> hasUnsavedChanges = true);

        // Navigation guard — MainWindow checks this before switching away
        root.getProperties().put("navigationGuard",
            (java.util.function.BooleanSupplier) this::confirmNavigation);

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
        Label title = new Label("Workflow Designer");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label("Define, save and run configurable security scanning workflows.");
        sub.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        titleBlock.getChildren().addAll(title, sub);

        header.getChildren().add(titleBlock);
        return header;
    }

    // ── Designer panel (left) ──────────────────────────────────────────────

    private VBox buildDesigner() {
        VBox panel = new VBox(14);
        panel.setPadding(new Insets(20, 20, 20, 28));
        panel.setStyle("-fx-background-color: " + BG + ";");

        // Name field
        Label nameLabel = sectionHeading("Workflow Name");
        nameField = new TextField("My Security Workflow");
        styleField(nameField);

        // Step palette
        Label paletteLabel = sectionHeading("Add Step");
        HBox palette = buildPalette();

        // Steps list
        Label stepsLabel = sectionHeading("Steps");
        VBox stepsList = buildStepsList();

        // Action buttons
        HBox actions = buildActionButtons();

        // Progress
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-accent: " + BLUE + ";");

        statusLabel = new Label("Add steps and click Run to execute the workflow.");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        statusLabel.setWrapText(true);

        panel.getChildren().addAll(
            nameLabel, nameField,
            paletteLabel, palette,
            stepsLabel, stepsList,
            actions, progressBar, statusLabel
        );
        return panel;
    }

    // ── Step palette ───────────────────────────────────────────────────────

    private HBox buildPalette() {
        HBox palette = new HBox(8);

        record StepDef(StepType type, String label, String color) {}

        List<StepDef> defs = List.of(
            new StepDef(StepType.SCAN,   "⊕ Scan",   " + BLUE + "),
            new StepDef(StepType.SELECT, "⊚ Select", " + TEAL + "),
            new StepDef(StepType.FILTER, "⊘ Filter", " + PURPLE + "),
            new StepDef(StepType.SAMPLE, "⊙ Sample", "#64748B"),
            new StepDef(StepType.TRIAGE, "◉ Triage", " + AMBER + "),
            new StepDef(StepType.SCORE,  "★ Score",  " + GREEN + "),
            new StepDef(StepType.REPORT, "≡ Report", " + RED + ")
        );

        for (StepDef def : defs) {
            Button btn = new Button(def.label());
            btn.setStyle(
                "-fx-background-color: " + CARD + "; -fx-text-fill: " + def.color() + "; "
                + "-fx-background-radius: 6; -fx-font-size: 11px; -fx-padding: 6 10; "
                + "-fx-border-color: " + def.color() + "; -fx-border-radius: 6; "
                + "-fx-border-width: 1;");
            btn.setOnAction(e -> addStep(def.type()));
            palette.getChildren().add(btn);
        }
        return palette;
    }

    // ── Steps list ─────────────────────────────────────────────────────────

    private VBox buildStepsList() {
        VBox list = new VBox(6);
        list.setPrefHeight(260);
        list.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 8; "
            + "-fx-padding: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 6, 0, 0, 2);");

        Label empty = new Label("No steps yet. Click a step type above to add one.");
        empty.setStyle("-fx-text-fill: #9CA3AF; -fx-font-size: 12px;");

        // Refresh the list whenever stepRows changes
        stepRows.addListener((javafx.collections.ListChangeListener<StepRow>) c -> {
            list.getChildren().clear();
            if (stepRows.isEmpty()) {
                list.getChildren().add(empty);
            } else {
                for (int i = 0; i < stepRows.size(); i++) {
                    list.getChildren().add(buildStepCard(stepRows.get(i), i));
                }
            }
            updateJsonPreview();
        });

        list.getChildren().add(empty);
        return list;
    }

    private HBox buildStepCard(StepRow row, int index) {
        final String styleNormal   = "-fx-background-color: " + SURFACE2 + "; -fx-background-radius: 6; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6;";
        final String styleDragOver = "-fx-background-color: " + BTN_BG_SECONDARY + "; -fx-background-radius: 6; "
            + "-fx-border-color: " + BLUE + "; -fx-border-radius: 6; -fx-border-width: 2;";

        HBox card = new HBox(10);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(8, 10, 8, 10));
        card.setStyle(styleNormal);

        // Drag handle
        Label dragHandle = new Label("⠿");
        dragHandle.setStyle("-fx-text-fill: #D1D5DB; -fx-font-size: 15px; -fx-cursor: open-hand;");

        // Step number badge
        Label num = new Label(String.valueOf(index + 1));
        num.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: white; "
            + "-fx-background-radius: 10; -fx-padding: 1 6; -fx-font-size: 10px;");

        Label typeLbl = new Label(row.type.name());
        typeLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: " + TEXT
            + "; -fx-font-size: 12px; -fx-min-width: 60px;");

        Label paramsLbl = new Label(row.paramsDisplay());
        paramsLbl.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 11px;");
        HBox.setHgrow(paramsLbl, Priority.ALWAYS);

        Button configBtn = new Button("⚙");
        configBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + MUTED
            + "; -fx-font-size: 13px; -fx-cursor: hand;");
        configBtn.setOnAction(e -> configureStep(row, index));

        Button removeBtn = new Button("✕");
        removeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + RED
            + "; -fx-font-size: 12px; -fx-cursor: hand;");
        removeBtn.setOnAction(e -> stepRows.remove(row));

        card.getChildren().addAll(dragHandle, num, typeLbl, paramsLbl, configBtn, removeBtn);

        // ── Drag-and-drop reordering ───────────────────────────────────────
        card.setOnDragDetected(e -> {
            Dragboard db = card.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(String.valueOf(index));
            db.setContent(cc);
            db.setDragView(card.snapshot(null, null));
            card.setOpacity(0.4);
            e.consume();
        });

        card.setOnDragDone(e -> {
            card.setOpacity(1.0);
            e.consume();
        });

        card.setOnDragOver(e -> {
            if (e.getGestureSource() != card && e.getDragboard().hasString()) {
                e.acceptTransferModes(TransferMode.MOVE);
            }
            e.consume();
        });

        card.setOnDragEntered(e -> {
            if (e.getGestureSource() != card && e.getDragboard().hasString()) {
                card.setStyle(styleDragOver);
            }
            e.consume();
        });

        card.setOnDragExited(e -> {
            card.setStyle(styleNormal);
            e.consume();
        });

        card.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasString()) {
                int from = Integer.parseInt(db.getString());
                if (from != index) {
                    StepRow moved = stepRows.remove(from);
                    stepRows.add(index, moved);
                }
                e.setDropCompleted(true);
            }
            e.consume();
        });

        return card;
    }

    // ── Action buttons ─────────────────────────────────────────────────────

    private HBox buildActionButtons() {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Button saveBtn  = primaryBtn("💾  Save Workflow");
        Button runBtn   = new Button("▶  Run Workflow");
        runBtn.setStyle("-fx-background-color: " + GREEN + "; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 16; "
            + "-fx-font-weight: bold;");
        stopWorkflowBtn = new Button("■  Stop Workflow");
        stopWorkflowBtn.setStyle("-fx-background-color: " + RED_LIGHT_BG + "; -fx-text-fill: " + RED + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 16; "
            + "-fx-border-color: " + RED_BORDER + "; -fx-border-radius: 6;");
        stopWorkflowBtn.setVisible(false);
        stopWorkflowBtn.setManaged(false);
        Button clearBtn = secondaryBtn("⊘  Clear");

        saveBtn.setOnAction(e -> saveWorkflow());
        runBtn .setOnAction(e -> runWorkflow());
        clearBtn.setOnAction(e -> { stepRows.clear(); nameField.setText("My Security Workflow"); hasUnsavedChanges = false; });
        stopWorkflowBtn.setOnAction(e -> {
            Thread t = currentWorkflowThread;
            if (t != null) t.interrupt();
            stopWorkflowBtn.setDisable(true);
            setStatus("Stopping workflow…");
        });

        row.getChildren().addAll(saveBtn, runBtn, stopWorkflowBtn, clearBtn);
        return row;
    }

    // ── Saved workflows panel (right) ──────────────────────────────────────

    private VBox buildSavedPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(20, 24, 20, 16));
        panel.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 0 1;");

        Label heading = new Label("Saved Workflows");
        heading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        savedList = new ListView<>(savedWorkflows);
        savedList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(WorkflowDefinition item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item.getName() + "  (" + item.getSteps().size() + " steps)");
            }
        });
        savedList.setStyle("-fx-background-radius: 6;");
        VBox.setVgrow(savedList, Priority.ALWAYS);

        HBox listActions = new HBox(8);
        Button loadBtn   = secondaryBtn("Load");
        Button deleteBtn = new Button("Delete");
        deleteBtn.setStyle("-fx-background-color: " + RED_LIGHT_BG + "; -fx-text-fill: " + RED + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 6 12; "
            + "-fx-border-color: " + RED_BORDER + "; -fx-border-radius: 6;");

        loadBtn.setOnAction(e -> loadSelected());
        deleteBtn.setOnAction(e -> deleteSelected());
        listActions.getChildren().addAll(loadBtn, deleteBtn);

        // JSON preview
        Label previewLabel = sectionHeading("JSON Preview");
        Label expandHint = new Label("double-click to expand");
        expandHint.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        HBox previewHeader = new HBox(8, previewLabel, expandHint);
        previewHeader.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        jsonPreview = new TextArea();
        jsonPreview.setEditable(false);
        jsonPreview.setPrefRowCount(8);
        jsonPreview.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 10px; "
            + "-fx-background-color: #1E1E2E; -fx-control-inner-background: #1E1E2E; "
            + "-fx-text-fill: #CDD6F4;");
        jsonPreview.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
                dialog.setTitle("Workflow JSON");
                dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
                dialog.getDialogPane().setPrefWidth(700);
                dialog.getDialogPane().setPrefHeight(540);

                TextArea full = new TextArea(jsonPreview.getText());
                full.setEditable(false);
                full.setWrapText(false);
                full.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 13px; "
                    + "-fx-background-color: #1E1E2E; -fx-control-inner-background: #1E1E2E; "
                    + "-fx-text-fill: #CDD6F4;");
                javafx.scene.layout.VBox.setVgrow(full, javafx.scene.layout.Priority.ALWAYS);

                javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(full);
                content.setPadding(new javafx.geometry.Insets(12));
                javafx.scene.layout.VBox.setVgrow(content, javafx.scene.layout.Priority.ALWAYS);
                dialog.getDialogPane().setContent(content);
                dialog.showAndWait();
            }
        });

        panel.getChildren().addAll(
            heading, savedList, listActions,
            new Separator(), previewHeader, jsonPreview
        );
        return panel;
    }

    // ── Step actions ───────────────────────────────────────────────────────

    private void addStep(StepType type) {
        StepRow row = new StepRow(type);

        // Set sensible defaults per type
        switch (type) {
            case SCAN   -> { row.params.put("scanner", "semgrep");
                             row.params.put("ruleset", "p/security-audit"); }
            case SELECT -> {
                row.params.put("scanner",      "all");
                row.params.put("repository",   "all");
                row.params.put("rule_pattern", "");
                row.params.put("severity",     "all");
            }
            case FILTER -> row.params.put("condition", "severity >= WARNING");
            case SAMPLE -> row.params.put("size", "1000");
            case TRIAGE -> {
                row.params.put("prompt_version", "v1.0");
                row.params.put("model",          ctx.getOllamaModel());
                row.params.put("run_name",       "Workflow Run");
            }
            case REPORT -> row.params.put("formats", "json,csv");
            default     -> {}
        }
        stepRows.add(row);
    }

    private void configureStep(StepRow row, int index) {
        if (row.type == StepType.TRIAGE) {
            configureTriageStep(row, index);
        } else if (row.type == StepType.SELECT) {
            configureSelectStep(row, index);
        } else {
            configureGenericStep(row, index);
        }
    }

    /** Dedicated config dialog for TRIAGE steps — shows a prompt ComboBox. */
    private void configureTriageStep(StepRow row, int index) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Configure Triage Step");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(440);

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);
        grid.setPadding(new Insets(20));
        grid.getColumnConstraints().addAll(
            columnPct(35), columnPct(65));

        int r = 0;

        // Prompt selector
        grid.add(boldLabel("Prompt Template"), 0, r);
        ComboBox<PromptTemplate> promptBox = new ComboBox<>();
        promptBox.setMaxWidth(Double.MAX_VALUE);
        java.util.List<PromptTemplate> templates = ctx.promptTemplateRepo().findAll();
        promptBox.getItems().setAll(templates);
        promptBox.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(PromptTemplate t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : t.getName() + "  (" + t.getVersion() + ")");
            }
        });
        promptBox.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(PromptTemplate t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? "Select…" : t.getName() + "  (" + t.getVersion() + ")");
            }
        });
        String currentVer = row.params.getOrDefault("prompt_version", "v1.0");
        templates.stream().filter(t -> currentVer.equals(t.getVersion()))
            .findFirst().ifPresent(t -> promptBox.getSelectionModel().select(t));
        if (promptBox.getSelectionModel().isEmpty() && !templates.isEmpty())
            promptBox.getSelectionModel().selectFirst();
        grid.add(promptBox, 1, r++);

        // Run name
        grid.add(boldLabel("Run Name"), 0, r);
        TextField runNameField = new TextField(row.params.getOrDefault("run_name", "Workflow Run"));
        styleField(runNameField);
        grid.add(runNameField, 1, r++);

        // Model
        grid.add(boldLabel("Model"), 0, r);
        TextField modelField = new TextField(row.params.getOrDefault("model", ctx.getOllamaModel()));
        styleField(modelField);
        grid.add(modelField, 1, r++);

        // URL (optional override)
        grid.add(boldLabel("Ollama URL (optional)"), 0, r);
        TextField urlField = new TextField(row.params.getOrDefault("url", ""));
        urlField.setPromptText("Leave blank to use Settings URL");
        styleField(urlField);
        grid.add(urlField, 1, r++);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                row.params.clear();
                PromptTemplate sel = promptBox.getSelectionModel().getSelectedItem();
                row.params.put("prompt_version", sel != null ? sel.getVersion() : "v1.0");
                String rn = runNameField.getText().trim();
                if (!rn.isBlank()) row.params.put("run_name", rn);
                String m = modelField.getText().trim();
                if (!m.isBlank()) row.params.put("model", m);
                String u = urlField.getText().trim().replaceAll("/+$", "");
                if (!u.isBlank()) row.params.put("url", u);
                stepRows.set(index, row);
            }
            return null;
        });
        dialog.showAndWait();
    }

    /** Dedicated config dialog for SELECT steps — structured filter criteria. */
    private void configureSelectStep(StepRow row, int index) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Configure Select Step");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(460);

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);
        grid.setPadding(new Insets(20));
        grid.getColumnConstraints().addAll(columnPct(38), columnPct(62));

        int r = 0;

        // Scanner
        grid.add(boldLabel("Scanner"), 0, r);
        ComboBox<String> scannerBox = new ComboBox<>(
            javafx.collections.FXCollections.observableArrayList(
                "all", "SEMGREP", "TRIVY", "GITLEAKS", "CODEQL", "SONARQUBE"));
        scannerBox.setValue(row.params.getOrDefault("scanner", "all"));
        scannerBox.setMaxWidth(Double.MAX_VALUE);
        grid.add(scannerBox, 1, r++);

        // Repository
        grid.add(boldLabel("Repository"), 0, r);
        java.util.List<String> repoNames = new java.util.ArrayList<>();
        repoNames.add("all");
        ctx.repositoryRepo().findAll().stream()
            .map(rep -> rep.getName() != null ? rep.getName() : "")
            .filter(n -> !n.isBlank())
            .forEach(repoNames::add);
        ComboBox<String> repoBox = new ComboBox<>(
            javafx.collections.FXCollections.observableArrayList(repoNames));
        String currentRepo = row.params.getOrDefault("repository", "all");
        repoBox.setValue(repoNames.contains(currentRepo) ? currentRepo : "all");
        repoBox.setMaxWidth(Double.MAX_VALUE);
        grid.add(repoBox, 1, r++);

        // Rule pattern
        grid.add(boldLabel("Rule contains"), 0, r);
        TextField ruleField = new TextField(row.params.getOrDefault("rule_pattern", ""));
        ruleField.setPromptText("e.g. xss, sql, template (empty = all)");
        styleField(ruleField);
        grid.add(ruleField, 1, r++);

        // Severity
        grid.add(boldLabel("Severity"), 0, r);
        ComboBox<String> sevBox = new ComboBox<>(
            javafx.collections.FXCollections.observableArrayList(
                "all", "ERROR", "WARNING", "INFO", "ERROR,WARNING"));
        sevBox.setValue(row.params.getOrDefault("severity", "all"));
        sevBox.setEditable(true);
        sevBox.setMaxWidth(Double.MAX_VALUE);
        grid.add(sevBox, 1, r++);

        // Info note
        Label note = new Label(
            "SELECT loads existing findings from the database matching these criteria "
            + "and passes them directly to TRIAGE — no scan needed.");
        note.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        note.setWrapText(true);
        GridPane.setColumnSpan(note, 2);
        grid.add(note, 0, r);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                row.params.clear();
                row.params.put("scanner",      scannerBox.getValue() != null ? scannerBox.getValue() : "all");
                row.params.put("repository",   repoBox.getValue()    != null ? repoBox.getValue()    : "all");
                row.params.put("rule_pattern", ruleField.getText().trim());
                row.params.put("severity",     sevBox.getValue()     != null ? sevBox.getValue()     : "all");
                stepRows.set(index, row);
            }
            return null;
        });
        dialog.showAndWait();
    }

    /** Generic key-value config dialog for all non-TRIAGE steps. */
    private void configureGenericStep(StepRow row, int index) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Configure " + row.type.name() + " Step");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(10);
        grid.setPadding(new Insets(16));

        List<TextField> keyFields = new ArrayList<>();
        List<TextField> valFields = new ArrayList<>();

        int rowNum = 0;
        grid.add(boldLabel("Parameter"), 0, rowNum);
        grid.add(boldLabel("Value"),     1, rowNum++);

        for (var entry : row.params.entrySet()) {
            TextField k = new TextField(entry.getKey());
            TextField v = new TextField(entry.getValue());
            styleField(k); styleField(v);
            keyFields.add(k); valFields.add(v);
            grid.add(k, 0, rowNum);
            grid.add(v, 1, rowNum++);
        }

        TextField newKey = new TextField(); newKey.setPromptText("key");
        TextField newVal = new TextField(); newVal.setPromptText("value");
        styleField(newKey); styleField(newVal);
        grid.add(newKey, 0, rowNum);
        grid.add(newVal, 1, rowNum);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                row.params.clear();
                for (int i = 0; i < keyFields.size(); i++) {
                    String k = keyFields.get(i).getText().trim();
                    String v = valFields.get(i).getText().trim();
                    if (!k.isBlank()) row.params.put(k, v);
                }
                if (!newKey.getText().isBlank()) {
                    row.params.put(newKey.getText().trim(), newVal.getText().trim());
                }
                stepRows.set(index, row);
            }
            return null;
        });
        dialog.showAndWait();
    }

    // ── Save / Load / Delete ───────────────────────────────────────────────

    private void saveWorkflow() {
        String name = nameField.getText().trim();
        if (name.isBlank()) { setStatus("Enter a workflow name."); return; }
        if (stepRows.isEmpty()) { setStatus("Add at least one step."); return; }

        // Check for an existing workflow with the same name
        Optional<WorkflowDefinition> existing = ctx.workflowRepo().findAll().stream()
            .filter(w -> w.getName().equalsIgnoreCase(name))
            .findFirst();

        if (existing.isPresent()) {
            Alert confirm = new Alert(Alert.AlertType.WARNING);
            confirm.setTitle("Duplicate Workflow Name");
            confirm.setHeaderText("\"" + name + "\" already exists.");
            confirm.setContentText("Do you want to overwrite it, or go back and rename?");
            ButtonType overwrite = new ButtonType("Overwrite", ButtonBar.ButtonData.OK_DONE);
            confirm.getButtonTypes().setAll(overwrite, ButtonType.CANCEL);
            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isEmpty() || choice.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) return;

            try {
                WorkflowDefinition def = buildDefinition();
                def.setId(existing.get().getId());
                parser.parse(parser.toJson(def));
                ctx.workflowRepo().update(def);
                loadSaved();
                hasUnsavedChanges = false;
                setStatus("Workflow \"" + name + "\" updated.");
            } catch (WorkflowParseException e) {
                setStatus("Validation error: " + e.getMessage());
            }
            return;
        }

        try {
            WorkflowDefinition def = buildDefinition();
            parser.parse(parser.toJson(def));
            ctx.workflowRepo().save(def);
            loadSaved();
            hasUnsavedChanges = false;
            setStatus("Workflow \"" + name + "\" saved.");
        } catch (WorkflowParseException e) {
            setStatus("Validation error: " + e.getMessage());
        }
    }

    private void loadSelected() {
        WorkflowDefinition selected = savedList.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        nameField.setText(selected.getName());
        stepRows.clear();
        selected.getSteps().forEach(step -> {
            StepRow row = new StepRow(step.getType());
            row.params.putAll(step.getParams());
            stepRows.add(row);
        });
        hasUnsavedChanges = false;
        setStatus("Loaded: " + selected.getName());
    }

    private void deleteSelected() {
        WorkflowDefinition selected = savedList.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Delete Workflow");
        confirm.setHeaderText("Delete \"" + selected.getName() + "\"?");
        confirm.setContentText("This workflow will be permanently deleted and cannot be recovered.");
        ButtonType deleteBtn = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(deleteBtn, ButtonType.CANCEL);

        confirm.showAndWait().ifPresent(btn -> {
            if (btn.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                ctx.workflowRepo().delete(selected.getId());
                loadSaved();
                setStatus("Deleted: " + selected.getName());
            }
        });
    }

    private void loadSaved() {
        savedWorkflows.setAll(ctx.workflowRepo().findAll());
    }

    // ── Run workflow ───────────────────────────────────────────────────────

    private void runWorkflow() {
        if (stepRows.isEmpty()) { setStatus("Add steps before running."); return; }

        // Pick a repository
        var repos = ctx.repositoryRepo().findAll();
        if (repos.isEmpty()) {
            setStatus("No repositories found. Add one in the Repositories screen first.");
            return;
        }

        // Simple repo picker if multiple
        com.vulntriage.domain.Repository repo;
        if (repos.size() == 1) {
            repo = repos.get(0);
        } else {
            ChoiceDialog<com.vulntriage.domain.Repository> picker =
                new ChoiceDialog<>(repos.get(0), repos);
            picker.setTitle("Select Repository");
            picker.setHeaderText("Select the repository to run this workflow against:");
            Optional<com.vulntriage.domain.Repository> chosen = picker.showAndWait();
            if (chosen.isEmpty()) return;
            repo = chosen.get();
        }

        WorkflowDefinition def;
        try {
            def = buildDefinition();
        } catch (Exception e) {
            setStatus("Workflow error: " + e.getMessage());
            return;
        }

        final com.vulntriage.domain.Repository finalRepo = repo;
        progressBar.setVisible(true);
        progressBar.setProgress(-1); // indeterminate
        ctx.setWorkflowRunning(true);
        stopWorkflowBtn.setVisible(true);
        stopWorkflowBtn.setManaged(true);
        stopWorkflowBtn.setDisable(false);

        UiProgressUpdater updater = new UiProgressUpdater();
        updater.statusProperty().addListener((o, old, msg) ->
            Platform.runLater(() -> setStatus(msg)));

        WorkflowStepFactory factory = new WorkflowStepFactory(
            List.of(new ProgressLogger(), updater));

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                currentWorkflowThread = Thread.currentThread();
                // Build stage chain from workflow definition
                List<PipelineStage> stages = new ArrayList<>();
                for (WorkflowStep step : def.getSteps()) {
                    stages.add(factory.create(step));
                }

                // Chain stages
                for (int i = 0; i < stages.size() - 1; i++) {
                    stages.get(i).setNext(stages.get(i + 1));
                }

                // Execute
                com.vulntriage.config.ScanConfig config =
                    com.vulntriage.config.ScanConfig.defaults();
                PipelineContext ctx = new PipelineContext(finalRepo, config);
                ctx.setStatus(com.vulntriage.domain.enums.PipelineStatus.RUNNING);

                if (!stages.isEmpty()) {
                    stages.get(0).execute(ctx);
                }
                return null;
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            ctx.setWorkflowRunning(false);
            stopWorkflowBtn.setVisible(false);
            stopWorkflowBtn.setManaged(false);
            boolean interrupted = Thread.interrupted();
            setStatus(interrupted ? "Workflow stopped." : "Workflow complete for " + finalRepo.getName() + ".");
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            ctx.setWorkflowRunning(false);
            stopWorkflowBtn.setVisible(false);
            stopWorkflowBtn.setManaged(false);
            setStatus("Workflow failed: " + task.getException().getMessage());
        }));

        new Thread(task, "workflow-thread").start();
    }

    // ── Navigation guard ───────────────────────────────────────────────────

    private boolean confirmNavigation() {
        if (!hasUnsavedChanges) return true;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText("You have unsaved workflow changes.");
        alert.setContentText("Save before leaving, or discard your changes?");

        ButtonType saveAndLeave = new ButtonType("Save & Leave");
        ButtonType discard      = new ButtonType("Discard");
        alert.getButtonTypes().setAll(saveAndLeave, discard, ButtonType.CANCEL);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) return false;

        if (result.get() == saveAndLeave) {
            saveWorkflow();
            hasUnsavedChanges = false;
        } else {
            // Discard: revert the form so it's clean if the user comes back
            WorkflowDefinition loaded = savedList.getSelectionModel().getSelectedItem();
            if (loaded != null) {
                loadSelected();  // restores saved state and resets hasUnsavedChanges
            } else {
                stepRows.clear();
                nameField.setText("My Security Workflow");
                hasUnsavedChanges = false;
            }
        }
        return true;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private WorkflowDefinition buildDefinition() {
        WorkflowDefinition def = new WorkflowDefinition(
            nameField.getText().trim(), "");
        for (StepRow row : stepRows) {
            WorkflowStep step = new WorkflowStep(row.type, new java.util.HashMap<>(row.params));
            def.addStep(step);
        }
        return def;
    }

    private void updateJsonPreview() {
        if (jsonPreview == null) return;
        try {
            WorkflowDefinition def = buildDefinition();
            jsonPreview.setText(parser.toJson(def));
        } catch (Exception e) {
            jsonPreview.setText("(invalid workflow)");
        }
    }

    private void setStatus(String msg) {
        if (statusLabel != null) statusLabel.setText(msg);
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

    private Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: " + DIM + "; -fx-font-size: 11px;");
        return l;
    }

    private javafx.scene.layout.ColumnConstraints columnPct(double pct) {
        javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
        cc.setPercentWidth(pct);
        cc.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        return cc;
    }

    private Button primaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 16;");
        return b;
    }

    private Button secondaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + BTN_BG_SECONDARY + "; -fx-text-fill: " + BLUE + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 14; "
            + "-fx-border-color: " + BTN_BORDER_SECONDARY + "; -fx-border-radius: 6;");
        return b;
    }

    // ── Inner model ────────────────────────────────────────────────────────

    static class StepRow {
        final StepType type;
        final java.util.Map<String, String> params = new java.util.LinkedHashMap<>();

        StepRow(StepType type) { this.type = type; }

        String paramsDisplay() {
            if (params.isEmpty()) return "(no params)";
            return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "  " + b)
                .orElse("");
        }
    }
}