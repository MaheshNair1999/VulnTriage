package com.vulntriage.ui.findings;

import com.vulntriage.app.AppContext;
import com.vulntriage.ui.UIUtils;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.Repository;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import javafx.scene.control.SelectionMode;
import javafx.scene.input.KeyCode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static com.vulntriage.config.ThemeColors.*;

/**
 * Findings Browser screen.
 *
 * Shows all findings in a filterable, sortable table.
 * Filters: repository dropdown + severity dropdown + free-text search.
 * Clicking a column header sorts by that column.
 * Clicking a finding shows its detail in a side panel.
 */
public class FindingsView {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM HH:mm");


    // Sentinel value for "All Repositories" option
    private static final long ALL_REPOS = -1L;

    private final AppContext ctx = AppContext.getInstance();
    private ObservableList<FindingRow> allRows;
    private FilteredList<FindingRow>   filteredRows;
    private SortedList<FindingRow>     sortedRows;
    private Label   statusLabel;
    private Label   countLabel;
    private Label   selectionLabel;
    private Button  refreshBtn;
    private Button  deleteBtn;
    private TableView<FindingRow> table;

    // Keep a reference so doRefresh can repopulate repo options
    private ComboBox<RepoOption> repoFilter;

    public Node build() {
        allRows      = FXCollections.observableArrayList();
        filteredRows = new FilteredList<>(allRows, r -> true);
        sortedRows   = new SortedList<>(filteredRows);
        loadFindings();

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG + ";");
        root.setTop   (buildToolbar());
        root.setCenter(buildTable());
        root.setBottom(buildStatusBar());
        return root;
    }

    // ── Toolbar ────────────────────────────────────────────────────────────

    private HBox buildToolbar() {
        HBox bar = new HBox(12);
        bar.setPadding(new Insets(18, 28, 14, 28));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: " + CARD_BG + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 1 0;");

        VBox titleBlock = new VBox(2);
        Label title = new Label("Findings");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label("Browse and search all vulnerability findings.");
        sub.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        countLabel = new Label();
        countLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + ACCENT + ";");
        titleBlock.getChildren().addAll(title, sub, countLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Repository filter
        repoFilter = new ComboBox<>();
        repoFilter.setStyle("-fx-font-size: 12px;");
        repoFilter.setPrefWidth(180);
        populateRepoFilter();

        // Scanner filter
        ComboBox<String> scannerFilter = new ComboBox<>();
        scannerFilter.getItems().addAll("All Scanners", "SEMGREP", "TRIVY", "GITLEAKS", "CODEQL", "SONARQUBE");
        scannerFilter.setValue("All Scanners");
        scannerFilter.setStyle("-fx-font-size: 12px;");

        // Severity filter
        ComboBox<String> severityFilter = new ComboBox<>();
        severityFilter.getItems().addAll("All Severities", "ERROR", "WARNING", "INFO");
        severityFilter.setValue("All Severities");
        severityFilter.setStyle("-fx-font-size: 12px;");

        // Search field
        TextField search = new TextField();
        search.setPromptText("Search rule, file, category…");
        search.setPrefWidth(220);
        search.setStyle("-fx-font-size: 12px; -fx-background-radius: 6px; "
            + "-fx-border-radius: 6px; -fx-border-color: " + BORDER + "; -fx-padding: 6 10;");

        // Wire all filters together
        Runnable applyFilter = () -> {
            RepoOption selectedRepo = repoFilter.getValue();
            long repoId = selectedRepo != null ? selectedRepo.id() : ALL_REPOS;
            String sev     = severityFilter.getValue();
            String scanner = scannerFilter.getValue();
            String query   = search.getText().toLowerCase().trim();

            filteredRows.setPredicate(row -> {
                boolean repoOk    = repoId == ALL_REPOS || row.getRepositoryId() == repoId;
                boolean sevOk     = sev.equals("All Severities") || row.getSeverity().equals(sev);
                boolean scannerOk = scanner.equals("All Scanners") || row.getSource().equals(scanner);
                boolean qOk       = query.isEmpty()
                    || row.getRuleId().toLowerCase().contains(query)
                    || row.getFilePath().toLowerCase().contains(query)
                    || row.getCategory().toLowerCase().contains(query)
                    || row.getMessage().toLowerCase().contains(query);
                return repoOk && sevOk && scannerOk && qOk;
            });
            updateStatus();
        };

        repoFilter.setOnAction(e -> applyFilter.run());
        scannerFilter.setOnAction(e -> applyFilter.run());
        severityFilter.setOnAction(e -> applyFilter.run());
        search.textProperty().addListener((o, old, n) -> applyFilter.run());

        refreshBtn = new Button("↻  Refresh");
        refreshBtn.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + DIM + "; "
            + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 6 14; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6px;");
        refreshBtn.setOnAction(e -> doRefresh(applyFilter));

        selectionLabel = new Label();
        selectionLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; "
            + "-fx-text-fill: #1D4ED8; -fx-background-color: " + BTN_BG_SECONDARY + "; "
            + "-fx-background-radius: 5; -fx-padding: 4 10;");
        selectionLabel.setVisible(false);
        selectionLabel.setManaged(false);

        deleteBtn = new Button("🗑  Delete");
        deleteBtn.setStyle("-fx-background-color: " + RED_LIGHT_BG + "; -fx-text-fill: #DC2626; "
            + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 6 14; "
            + "-fx-border-color: " + RED_BORDER + "; -fx-border-radius: 6px;");
        deleteBtn.setVisible(false);
        deleteBtn.setManaged(false);
        deleteBtn.setOnAction(e -> deleteSelected());

        bar.getChildren().addAll(titleBlock, spacer, selectionLabel, deleteBtn, repoFilter, scannerFilter, severityFilter, search, refreshBtn);
        return bar;
    }

    // ── Repo filter population ─────────────────────────────────────────────

    private void populateRepoFilter() {
        RepoOption current = repoFilter.getValue();
        repoFilter.getItems().clear();
        repoFilter.getItems().add(new RepoOption(ALL_REPOS, "All Repositories"));
        List<Repository> repos = ctx.repositoryRepo().findAll();
        for (Repository r : repos) {
            repoFilter.getItems().add(new RepoOption(r.getId(), r.getName()));
        }
        // Restore selection if still valid, otherwise default to All
        if (current != null && repoFilter.getItems().stream().anyMatch(o -> o.id() == current.id())) {
            repoFilter.getItems().stream()
                .filter(o -> o.id() == current.id())
                .findFirst()
                .ifPresent(repoFilter::setValue);
        } else {
            repoFilter.setValue(repoFilter.getItems().get(0));
        }
    }

    // ── Refresh with spinner animation ────────────────────────────────────

    private void doRefresh(Runnable applyFilter) {
        refreshBtn.setDisable(true);
        refreshBtn.setText("↻  Loading…");
        refreshBtn.setStyle("-fx-background-color: " + BTN_BG_SECONDARY + "; -fx-text-fill: " + ACCENT + "; "
            + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 6 14; "
            + "-fx-border-color: " + BTN_BORDER_SECONDARY + "; -fx-border-radius: 6px;");

        javafx.animation.Timeline spinner = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(200), ev -> {
                String current = refreshBtn.getText();
                String[] frames = {"↻  Loading…", "↺  Loading…"};
                refreshBtn.setText(current.startsWith("↻") ? frames[1] : frames[0]);
            })
        );
        spinner.setCycleCount(javafx.animation.Animation.INDEFINITE);
        spinner.play();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                javafx.application.Platform.runLater(() -> {
                    loadFindings();
                    populateRepoFilter();
                    applyFilter.run();
                });
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            spinner.stop();
            refreshBtn.setText("↻  Refresh");
            refreshBtn.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + DIM + "; "
                + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 6 14; "
                + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6px;");
            refreshBtn.setDisable(false);
            updateStatus();
        });

        task.setOnFailed(e -> {
            spinner.stop();
            refreshBtn.setText("↻  Refresh");
            refreshBtn.setStyle("-fx-background-color: " + RED_LIGHT_BG + "; -fx-text-fill: #DC2626; "
                + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 6 14; "
                + "-fx-border-color: " + RED_BORDER + "; -fx-border-radius: 6px;");
            refreshBtn.setDisable(false);
        });

        Thread t = new Thread(task, "findings-refresh");
        t.setDaemon(true);
        t.start();
    }

    // ── Table ──────────────────────────────────────────────────────────────

    private Node buildTable() {
        table = new TableView<>(sortedRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setStyle("-fx-background-color: " + CARD + ";");
        table.setPlaceholder(new Label("No findings match the current filter."));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        sortedRows.comparatorProperty().bind(table.comparatorProperty());

        TableColumn<FindingRow, Number> numCol = new TableColumn<>("#");
        numCol.setCellValueFactory(new PropertyValueFactory<>("reviewNum"));
        numCol.setMinWidth(38);
        numCol.setPrefWidth(38);
        numCol.setMaxWidth(38);
        numCol.setResizable(false);
        numCol.setSortable(true);
        numCol.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item.toString());
                setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 11px; "
                    + "-fx-font-family: 'Courier New'; -fx-alignment: CENTER-RIGHT;");
            }
        });

        TableColumn<FindingRow, String> srcCol      = col("Source",   "source",    90);
        TableColumn<FindingRow, String> repoCol     = col("Repo",     "repoName",  110);
        TableColumn<FindingRow, String> sevCol      = colWithBadge("Severity", "severity", 90);
        TableColumn<FindingRow, String> catCol      = col("Category", "category",  100);
        TableColumn<FindingRow, String> ruleCol     = col("Rule ID",  "ruleId",    220);
        TableColumn<FindingRow, String> fileCol     = col("File",     "filePath",  180);
        TableColumn<FindingRow, String> lineCol     = col("Line",     "lineStr",    55);
        TableColumn<FindingRow, String> scannedCol  = col("Scanned",  "scannedAt", 110);
        TableColumn<FindingRow, String> msgCol      = col("Message",  "message",   200);
        msgCol.setMaxWidth(260);

        // Source cell — colour-coded by scanner
        srcCol.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                String color = switch (item) {
                    case "TRIVY"     -> AMBER;
                    case "GITLEAKS"  -> AMBER_EXTRA;
                    case "CODEQL"    -> GREEN;
                    case "SONARQUBE" -> RED;
                    default          -> BLUE;
                };
                setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold; -fx-font-size: 10px;");
            }
        });

        // Severity cell with coloured badge
        sevCol.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                Label badge = new Label(item);
                badge.setPadding(new Insets(2, 7, 2, 7));
                badge.setStyle("-fx-background-radius: 10; -fx-font-size: 10px; "
                    + "-fx-font-weight: bold; "
                    + "-fx-background-color: " + badgeColor(item) + "; "
                    + "-fx-text-fill: " + badgeText(item) + ";");
                setGraphic(badge);
                setText(null);
            }
        });

        // Double-click opens detail popup
        table.setRowFactory(tv -> {
            TableRow<FindingRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    showDetailPopup(row.getItem());
                }
            });
            return row;
        });

        table.getColumns().addAll(numCol, srcCol, repoCol, sevCol, catCol, ruleCol, fileCol, lineCol, scannedCol, msgCol);

        table.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<FindingRow>) c -> {
                int n = table.getSelectionModel().getSelectedItems().size();
                boolean any = n > 0;
                selectionLabel.setText(n + " selected");
                selectionLabel.setVisible(any);
                selectionLabel.setManaged(any);
                deleteBtn.setVisible(any);
                deleteBtn.setManaged(any);
            });

        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE && !table.getSelectionModel().isEmpty()) {
                deleteSelected();
                event.consume();
            }
        });

        StackPane pane = new StackPane(table);
        pane.setPadding(new Insets(16, 28, 0, 28));
        pane.setStyle("-fx-background-color: " + BG + ";");
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    // ── Detail popup ───────────────────────────────────────────────────────

    private void showDetailPopup(FindingRow row) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Finding Detail");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(680);
        UIUtils.applyTheme(dialog);

        VBox content = new VBox(16);
        content.setPadding(new Insets(20));

        // Top badge row — severity, scanner, repo, and CVSS score when present
        HBox badges = new HBox(10);
        badges.setAlignment(Pos.CENTER_LEFT);
        badges.getChildren().addAll(
            makeBadge(row.getSeverity(), badgeColor(row.getSeverity()), badgeText(row.getSeverity())),
            makeBadge(row.getSource(),   scannerBg(row.getSource()),    scannerFg(row.getSource())),
            makeBadge(row.getRepoName(), "" + NEUTRAL_BG + "", "" + DIM + "")
        );
        if (row.getCvssScore() != null) {
            double s = row.getCvssScore();
            String cvssLabel = "CVSS " + String.format("%.1f", s);
            String cvssBg = s >= 9.0 ? "" + RED_BG + "" : s >= 7.0 ? "" + AMBER_BG + "" : s >= 4.0 ? "" + BTN_BG_SECONDARY + "" : "" + NEUTRAL_BG + "";
            String cvssFg = s >= 9.0 ? "" + RED_DIM + "" : s >= 7.0 ? "" + AMBER_DIM + "" : s >= 4.0 ? "" + BTN_FG_SECONDARY + "" : "" + DIM + "";
            badges.getChildren().add(makeBadge(cvssLabel, cvssBg, cvssFg));
        }

        // Rule + file
        Label ruleVal = fieldLabel(row.getRuleId());
        Label fileVal = fieldLabel(row.getFilePath() + (row.getLineStr().equals("—") ? "" : "  :  line " + row.getLineStr()));
        fileVal.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: 'Courier New';");

        // Category
        Label catRow = fieldLabel("Category: " + row.getCategory());

        // Message
        Label msgHead = sectionHead("Message");
        Label msgVal  = new Label(row.getMessage());
        msgVal.setWrapText(true);
        msgVal.setStyle("-fx-font-size: 13px; -fx-text-fill: " + TEXT + ";");

        // Code snippet
        Label codeHead = sectionHead("Code Snippet");
        Label expandHint = new Label("⤢ double-click to expand");
        expandHint.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        HBox codeHeader = new HBox(8, codeHead, expandHint);
        codeHeader.setAlignment(Pos.CENTER_LEFT);
        TextArea codeArea = new TextArea(
            row.getCodeSnippet().isBlank() ? "(no code snippet)" : row.getCodeSnippet());
        codeArea.setEditable(false);
        codeArea.setWrapText(false);
        codeArea.setPrefRowCount(12);
        codeArea.getStyleClass().add("code-snippet");
        codeArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 12px; -fx-text-fill: #CDD6F4;");
        codeArea.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) showFullCodeDialog(row);
        });

        content.getChildren().addAll(badges, ruleVal, fileVal, catRow,
            new Separator(), msgHead, msgVal, codeHeader, codeArea);
        dialog.getDialogPane().setContent(content);
        UIUtils.fixCodeSnippetBackground(dialog, codeArea);
        dialog.showAndWait();
    }

    private void showFullCodeDialog(FindingRow row) {
        String content;
        try {
            content = java.nio.file.Files.readString(java.nio.file.Paths.get(row.getFilePath()));
        } catch (Exception ex) {
            content = row.getCodeSnippet().isBlank() ? "(no code snippet)" : row.getCodeSnippet();
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Full File");
        dialog.setHeaderText(row.getFilePath());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(800);
        dialog.getDialogPane().setPrefHeight(640);
        UIUtils.applyTheme(dialog);

        TextArea fileArea = new TextArea(content);
        fileArea.setEditable(false);
        fileArea.setWrapText(false);
        fileArea.getStyleClass().add("code-snippet");
        fileArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 12px; -fx-text-fill: #CDD6F4;");

        final String finalContent = content;
        Button copyBtn = new Button("Copy All");
        copyBtn.setStyle("-fx-background-color: #1D4ED8; -fx-text-fill: white; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 6 16;");
        copyBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(finalContent);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("Copied ✓");
        });

        VBox body = new VBox(8);
        body.setPadding(new Insets(12));
        HBox toolbar = new HBox(copyBtn);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        VBox.setVgrow(fileArea, Priority.ALWAYS);
        body.getChildren().addAll(toolbar, fileArea);

        dialog.getDialogPane().setContent(body);
        UIUtils.fixCodeSnippetBackground(dialog, fileArea);
        dialog.showAndWait();
    }

    private Label makeBadge(String text, String bg, String fg) {
        Label l = new Label(text);
        l.setPadding(new Insets(3, 10, 3, 10));
        l.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; "
            + "-fx-background-radius: 10; -fx-font-size: 11px; -fx-font-weight: bold;");
        return l;
    }

    private Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + "; -fx-font-family: 'Courier New';");
        l.setWrapText(true);
        return l;
    }

    private Label sectionHead(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #9CA3AF;");
        return l;
    }

    private String scannerBg(String source) {
        return switch (source) {
            case "TRIVY"     -> "" + AMBER_BG + "";
            case "GITLEAKS"  -> "" + AMBER_BG + "";
            case "CODEQL"    -> "" + GREEN_BG + "";
            case "SONARQUBE" -> "" + RED_BG + "";
            default          -> "" + BLUE_BG + ""; // SEMGREP
        };
    }

    private String scannerFg(String source) {
        return switch (source) {
            case "TRIVY"     -> "" + AMBER_DIM + "";
            case "GITLEAKS"  -> "" + AMBER_EXTRA + "";
            case "CODEQL"    -> "" + GREEN_DIM + "";
            case "SONARQUBE" -> "" + RED_DIM + "";
            default          -> "" + BTN_FG_SECONDARY + ""; // SEMGREP
        };
    }

    // ── Status bar ─────────────────────────────────────────────────────────

    private HBox buildStatusBar() {
        HBox bar = new HBox();
        bar.setPadding(new Insets(6, 28, 8, 28));
        bar.setStyle("-fx-background-color: " + CARD_BG + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 1 0 0 0;");
        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        bar.getChildren().add(statusLabel);
        updateStatus();
        return bar;
    }

    // ── Data loading ───────────────────────────────────────────────────────

    private void loadFindings() {
        allRows.clear();
        int[] counter = {1};
        ctx.repositoryRepo().findAll().forEach(repo -> {
            Map<Long, String> scanDates = new HashMap<>();
            ctx.scanRunRepo().findByRepositoryId(repo.getId()).forEach(sr ->
                scanDates.put(sr.getId(), sr.getStartedAt() != null
                    ? sr.getStartedAt().format(DATE_FMT) : "—")
            );
            ctx.findingRepo().findByRepositoryId(repo.getId()).forEach(f ->
                allRows.add(toRow(counter[0]++, f, repo.getName(), scanDates))
            );
        });
    }

    private FindingRow toRow(int reviewNum, Finding f, String repoName, Map<Long, String> scanDates) {
        return new FindingRow(
            reviewNum,
            f.getId(),
            f.getRepositoryId(),
            repoName,
            f.getSource()     != null ? f.getSource().name()             : "SEMGREP",
            f.getSeverity()   != null ? f.getSeverity().name()           : "INFO",
            f.getCategory()   != null ? f.getCategory()                  : "",
            f.getRuleId()     != null ? f.getRuleId()                    : "",
            f.getFilePath()   != null ? f.getFilePath()                  : "",
            f.getLineNumber() != null ? String.valueOf(f.getLineNumber()) : "—",
            f.getMessage()    != null ? f.getMessage()                   : "",
            f.getCodeSnippet()!= null ? f.getCodeSnippet()               : "",
            f.getCvssScore(),
            scanDates.getOrDefault(f.getScanRunId(), "—")
        );
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    private void deleteSelected() {
        List<FindingRow> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;

        int n = selected.size();
        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Delete Findings");
        confirm.setHeaderText("Delete " + n + " finding" + (n == 1 ? "" : "s") + "?");
        confirm.setContentText(
            "This will permanently remove " + (n == 1 ? "this finding" : "these " + n + " findings")
            + " and any associated reviews and LLM results.\n\nThis cannot be undone.");
        confirm.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.OK)).setText("Delete");

        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.OK) return;
            for (FindingRow row : selected) {
                ctx.findingRepo().deleteById(row.getId());
            }
            loadFindings();
            updateStatus();
            selectionLabel.setVisible(false);
            selectionLabel.setManaged(false);
            deleteBtn.setVisible(false);
            deleteBtn.setManaged(false);
        });
    }

    private void updateStatus() {
        int shown = filteredRows.size();
        int total = allRows.size();
        RepoOption sel = repoFilter != null ? repoFilter.getValue() : null;
        String repoLabel = (sel == null || sel.id() == ALL_REPOS) ? "all repositories" : sel.name();
        statusLabel.setText(shown + " of " + total + " findings — " + repoLabel);
        if (countLabel != null) {
            countLabel.setText(shown + " finding" + (shown == 1 ? "" : "s") + " found");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private <T> TableColumn<FindingRow, T> col(String title, String prop, int w) {
        TableColumn<FindingRow, T> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(prop));
        if (w > 0) col.setPrefWidth(w);
        col.setSortable(true);
        return col;
    }

    private <T> TableColumn<FindingRow, T> colWithBadge(String title, String prop, int w) {
        TableColumn<FindingRow, T> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(prop));
        col.setPrefWidth(w);
        col.setSortable(true);
        return col;
    }

    private String badgeColor(String severity) {
        return switch (severity) {
            case "ERROR"   -> "" + RED_BG + "";
            case "WARNING" -> "" + AMBER_BG + "";
            default        -> "" + BTN_BG_SECONDARY + "";
        };
    }

    private String badgeText(String severity) {
        return switch (severity) {
            case "ERROR"   -> "" + RED_DIM + "";
            case "WARNING" -> "" + AMBER_DIM + "";
            default        -> "" + BTN_FG_SECONDARY + "";
        };
    }

    // ── RepoOption (combo item) ────────────────────────────────────────────

    public record RepoOption(long id, String name) {
        @Override public String toString() { return name; }
    }

    // ── Row model ──────────────────────────────────────────────────────────

    public static class FindingRow {
        private final int    reviewNum;
        private final long   id;
        private final long   repositoryId;
        private final String repoName;
        private final String source;
        private final String severity;
        private final String category;
        private final String ruleId;
        private final String filePath;
        private final String lineStr;
        private final String message;
        private final String codeSnippet;
        private final Double cvssScore;   // null when not applicable
        private final String scannedAt;

        public FindingRow(int reviewNum, long id, long repositoryId, String repoName,
                          String source, String severity, String category,
                          String ruleId, String filePath, String lineStr,
                          String message, String codeSnippet, Double cvssScore,
                          String scannedAt) {
            this.reviewNum    = reviewNum;
            this.id           = id;
            this.repositoryId = repositoryId;
            this.repoName     = repoName;
            this.source       = source;
            this.severity     = severity;
            this.category     = category;
            this.ruleId       = ruleId;
            this.filePath     = filePath;
            this.lineStr      = lineStr;
            this.message      = message;
            this.codeSnippet  = codeSnippet;
            this.cvssScore    = cvssScore;
            this.scannedAt    = scannedAt;
        }

        public int    getReviewNum()    { return reviewNum; }
        public long   getId()           { return id; }
        public long   getRepositoryId() { return repositoryId; }
        public String getRepoName()     { return repoName; }
        public String getSource()       { return source; }
        public String getSeverity()     { return severity; }
        public String getCategory()     { return category; }
        public String getRuleId()       { return ruleId; }
        public String getFilePath()     { return filePath; }
        public String getLineStr()      { return lineStr; }
        public String getMessage()      { return message; }
        public String getCodeSnippet()  { return codeSnippet; }
        public Double getCvssScore()    { return cvssScore; }
        public String getScannedAt()    { return scannedAt; }
    }
}