package com.vulntriage.ui.repository;

import com.vulntriage.app.AppContext;
import com.vulntriage.config.ScanConfig;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.Repository;
import com.vulntriage.domain.ScanRun;
import com.vulntriage.domain.enums.PipelineStatus;
import com.vulntriage.normaliser.FindingNormaliser;
import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerException;
import com.vulntriage.scanner.codeql.CodeQLAdapter;
import com.vulntriage.scanner.gitleaks.GitleaksAdapter;
import com.vulntriage.scanner.semgrep.SemgrepAdapter;
import com.vulntriage.scanner.sonarqube.SonarQubeAdapter;
import com.vulntriage.scanner.trivy.TrivyAdapter;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Repository Manager screen.
 *
 * Scan button runs SAST (Semgrep) and SCA (Trivy if available) directly —
 * no LLM triage, no sampling. Just scan → normalise → save to DB.
 * This keeps the scan fast and lets users review findings before triaging.
 */
public class RepositoryView {

    private static final String BG      = "#F1F5F9";
    private static final String CARD_BG = "#FFFFFF";
    private static final String ACCENT  = "#1D4ED8";
    private static final String TEXT    = "#111827";
    private static final String MUTED   = "#6B7280";

    private final AppContext ctx = AppContext.getInstance();
    private final ObservableList<RepositoryRow> rows = FXCollections.observableArrayList();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private TableView<RepositoryRow> table;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button scanBtn;
    private Button stopBtn;
    private volatile Thread currentScanThread;

    public Node build() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");

        Node toolbar   = buildToolbar();
        Node tableArea = buildTableArea();
        VBox.setVgrow(tableArea, Priority.ALWAYS);

        root.getChildren().addAll(toolbar, tableArea);
        refresh();
        return root;
    }

    // ── Toolbar ────────────────────────────────────────────────────────────

    private HBox buildToolbar() {
        HBox bar = new HBox(10);
        bar.setPadding(new Insets(20, 28, 16, 28));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: " + CARD_BG + "; "
            + "-fx-border-color: #E5E7EB; -fx-border-width: 0 0 1 0;");

        VBox titleBlock = new VBox(2);
        Label title = new Label("Repositories");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label("Add repositories and run scans.");
        sub.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        titleBlock.getChildren().addAll(title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addBtn = primaryBtn("＋  Add Repository");
        scanBtn = secondaryBtn("▶  Scan Selected");
        stopBtn = dangerBtn("■  Stop Scan");
        stopBtn.setVisible(false);
        stopBtn.setManaged(false);
        Button delBtn = dangerBtn("✕  Remove");

        addBtn.setOnAction(e  -> showAddDialog());
        scanBtn.setOnAction(e -> scanSelected());
        delBtn.setOnAction(e  -> removeSelected());
        stopBtn.setOnAction(e -> {
            cancelRequested.set(true);
            Thread t = currentScanThread;
            if (t != null) t.interrupt();
            stopBtn.setDisable(true);
            setStatus("Stopping scan…");
        });

        bar.getChildren().addAll(titleBlock, spacer, delBtn, scanBtn, stopBtn, addBtn);
        return bar;
    }

    // ── Table area ─────────────────────────────────────────────────────────

    private VBox buildTableArea() {
        VBox area = new VBox(10);
        area.setPadding(new Insets(20, 28, 20, 28));
        area.setStyle("-fx-background-color: " + BG + ";");
        VBox.setVgrow(area, Priority.ALWAYS);

        table = new TableView<>(rows);
        table.setStyle("-fx-background-color: white; -fx-background-radius: 8px; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 6, 0, 0, 2);");
        table.setPlaceholder(new Label("No repositories yet. Click '+ Add Repository' to begin."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<RepositoryRow, String> nameCol = col("Name",         "name",        180);
        TableColumn<RepositoryRow, String> pathCol = col("Local Path",   "localPath",   300);
        TableColumn<RepositoryRow, String> scanCol = col("Last Scanned", "lastScanned", 160);
        TableColumn<RepositoryRow, String> cntCol  = col("Findings",     "findingCount", 80);
        table.getColumns().addAll(nameCol, pathCol, scanCol, cntCol);

        table.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.DELETE
                    && !table.getSelectionModel().isEmpty()) {
                removeSelected();
                event.consume();
            }
        });

        // Progress bar (hidden until scan starts)
        progressBar = new ProgressBar(-1);  // indeterminate
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-accent: " + ACCENT + ";");

        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");

        area.getChildren().addAll(table, progressBar, statusLabel);
        return area;
    }

    // ── Add dialog ─────────────────────────────────────────────────────────

    private void showAddDialog() {
        Dialog<Repository> dialog = new Dialog<>();
        dialog.setTitle("Add Repository");
        dialog.setHeaderText("Register a local code repository for scanning.");

        ButtonType addType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);
        grid.setPadding(new Insets(20, 24, 20, 24));
        grid.setPrefWidth(480);

        TextField nameField = new TextField();
        nameField.setPromptText("e.g. DefectDojo");
        TextField pathField = new TextField();
        pathField.setPromptText("e.g. C:\\repos\\defectdojo");
        TextField urlField  = new TextField();
        urlField.setPromptText("https://github.com/... (optional)");

        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select Repository Root Folder");
            Window owner = dialog.getDialogPane().getScene().getWindow();
            File selected = dc.showDialog(owner);
            if (selected != null) {
                pathField.setText(selected.getAbsolutePath());
                if (nameField.getText().isBlank()) {
                    nameField.setText(selected.getName());
                }
            }
        });

        HBox pathRow = new HBox(8, pathField, browse);
        HBox.setHgrow(pathField, Priority.ALWAYS);

        grid.addRow(0, fieldLabel("Name"), nameField);
        grid.addRow(1, fieldLabel("Path"), pathRow);
        grid.addRow(2, fieldLabel("Remote URL"), urlField);
        ColumnConstraints cc = new ColumnConstraints(100);
        grid.getColumnConstraints().add(cc);

        dialog.getDialogPane().setContent(grid);

        Node addButton = dialog.getDialogPane().lookupButton(addType);
        addButton.setDisable(true);
        nameField.textProperty().addListener((o, old, n) ->
            addButton.setDisable(n.isBlank() || pathField.getText().isBlank()));
        pathField.textProperty().addListener((o, old, n) ->
            addButton.setDisable(n.isBlank() || nameField.getText().isBlank()));

        dialog.setResultConverter(btn -> {
            if (btn == addType) {
                String url = urlField.getText().isBlank() ? null : urlField.getText().trim();
                return new Repository(
                    nameField.getText().trim(),
                    pathField.getText().trim(),
                    url
                );
            }
            return null;
        });

        Optional<Repository> result = dialog.showAndWait();
        result.ifPresent(repo -> {
            ctx.repositoryRepo().save(repo);
            refresh();
            setStatus("Repository '" + repo.getName() + "' added. Select it and click Scan.");
        });
    }

    // ── Scanner picker dialog ──────────────────────────────────────────────

    private record ScannerChoice(
        boolean semgrep, boolean trivy, boolean gitleaks, boolean codeql, boolean sonarqube,
        String sonarUrl, String sonarToken
    ) {}

    private ScannerChoice showScannerPicker() {
        Dialog<ScannerChoice> dialog = new Dialog<>();
        dialog.setTitle("Select Scanners");
        dialog.setHeaderText("Choose which scanners to run:");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        CheckBox semgrepBox  = new CheckBox("Semgrep  (SAST — static analysis)");
        semgrepBox.setSelected(false);
        semgrepBox.setStyle("-fx-text-fill: " + TEXT + ";");

        CheckBox trivyBox    = new CheckBox("Trivy  (SCA — dependency vulnerabilities)");
        trivyBox.setSelected(false);
        trivyBox.setStyle("-fx-text-fill: " + TEXT + ";");

        CheckBox gitleaksBox = new CheckBox("Gitleaks  (secrets & credential detection)");
        gitleaksBox.setSelected(false);
        gitleaksBox.setStyle("-fx-text-fill: " + TEXT + ";");

        CheckBox codeqlBox   = new CheckBox("CodeQL  (deep SAST — requires CodeQL CLI)");
        codeqlBox.setSelected(false);
        codeqlBox.setStyle("-fx-text-fill: " + TEXT + ";");

        CheckBox sonarBox    = new CheckBox("SonarQube  (requires running server)");
        sonarBox.setSelected(false);
        sonarBox.setStyle("-fx-text-fill: " + TEXT + ";");

        // SonarQube config fields — only visible when sonarBox is checked
        Label urlLabel   = new Label("Server URL:");
        urlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT + ";");
        javafx.scene.control.TextField urlField = new javafx.scene.control.TextField("http://localhost:9000");
        urlField.setPromptText("http://localhost:9000");

        Label tokenLabel = new Label("Auth Token:");
        tokenLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT + ";");
        javafx.scene.control.PasswordField tokenField = new javafx.scene.control.PasswordField();
        tokenField.setPromptText("User token from SonarQube → Administration → Security");

        VBox sonarConfig = new VBox(6, urlLabel, urlField, tokenLabel, tokenField);
        sonarConfig.setPadding(new Insets(6, 0, 0, 24));
        sonarConfig.setVisible(false);
        sonarConfig.setManaged(false);
        sonarBox.selectedProperty().addListener((o, old, val) -> {
            sonarConfig.setVisible(val);
            sonarConfig.setManaged(val);
        });

        VBox content = new VBox(10);
        content.setPadding(new Insets(16, 24, 8, 24));

        Label available = new Label("AVAILABLE");
        available.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        content.getChildren().addAll(
            available,
            semgrepBox, trivyBox, gitleaksBox, codeqlBox, sonarBox, sonarConfig
        );

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(320);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        dialog.getDialogPane().setContent(scroll);
        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            return new ScannerChoice(
                semgrepBox.isSelected(), trivyBox.isSelected(),
                gitleaksBox.isSelected(), codeqlBox.isSelected(), sonarBox.isSelected(),
                urlField.getText().trim(), tokenField.getText().trim()
            );
        });

        return dialog.showAndWait().orElse(null);
    }

    // ── Scan ───────────────────────────────────────────────────────────────

    private void scanSelected() {
        List<RepositoryRow> selected = new java.util.ArrayList<>(
            table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showInfo("Select one or more repositories from the table first.");
            return;
        }

        // Resolve all selected repos and validate paths up-front
        List<Repository> repos = new java.util.ArrayList<>();
        for (RepositoryRow row : selected) {
            Optional<Repository> repoOpt = ctx.repositoryRepo().findById(row.getId());
            if (repoOpt.isEmpty()) continue;
            Repository repo = repoOpt.get();
            if (!new File(repo.getLocalPath()).isDirectory()) {
                showError("Path not found",
                    "The path for \"" + repo.getName() + "\" does not exist:\n" + repo.getLocalPath());
                return;
            }
            repos.add(repo);
        }
        if (repos.isEmpty()) return;

        ScannerChoice choice = showScannerPicker();
        if (choice == null) return;

        if (!choice.semgrep() && !choice.trivy() && !choice.gitleaks()
                && !choice.codeql() && !choice.sonarqube()) {
            showInfo("No scanners selected. Please select at least one scanner.");
            return;
        }

        if (choice.sonarqube() && (choice.sonarUrl().isBlank() || choice.sonarToken().isBlank())) {
            showError("SonarQube config missing",
                "Please enter both the SonarQube server URL and an auth token.");
            return;
        }

        String repoNames = repos.stream().map(Repository::getName)
            .collect(java.util.stream.Collectors.joining(", "));
        cancelRequested.set(false);
        setScanningState(true, "Scanning " + repoNames + "…");
        ctx.setScanRunning(true);

        Task<Void> scanTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                currentScanThread = Thread.currentThread();
                for (int i = 0; i < repos.size(); i++) {
                    if (cancelRequested.get()) break;
                    Repository repo = repos.get(i);
                    String prefix = repos.size() > 1
                        ? "[" + (i + 1) + "/" + repos.size() + "] " + repo.getName() + " — "
                        : "";
                    scanRepo(repo, choice, prefix);
                }
                return null;
            }
        };

        scanTask.messageProperty().addListener((o, old, msg) ->
            Platform.runLater(() -> setStatus(msg)));

        scanTask.setOnSucceeded(e -> Platform.runLater(() -> {
            ctx.setScanRunning(false);
            String msg = cancelRequested.get() ? "Scan stopped." : "Scan complete for: " + repoNames;
            setScanningState(false, msg);
            refresh();
        }));

        scanTask.setOnFailed(e -> Platform.runLater(() -> {
            ctx.setScanRunning(false);
            Throwable ex = scanTask.getException();
            setScanningState(false, "Scan failed: " + ex.getMessage());
            showError("Scan failed", ex.getMessage());
        }));

        Thread t = new Thread(scanTask, "scan-thread");
        t.setDaemon(true);
        t.start();
    }

    private void scanRepo(Repository repo, ScannerChoice choice, String prefix) throws Exception {
        FindingNormaliser normaliser = new FindingNormaliser();

        if (cancelRequested.get()) return;

        if (choice.semgrep()) {
            ScanRun run = new ScanRun(repo.getId(), com.vulntriage.domain.enums.ScannerType.SEMGREP);
            ctx.scanRunRepo().save(run);
            try {
                Platform.runLater(() -> setStatus(prefix + "Running Semgrep…"));
                List<RawFinding> raw = new SemgrepAdapter().scan(repo.getLocalPath(), ScanConfig.semgrepOnly());
                int count = 0;
                for (Finding f : normaliser.normaliseAll(raw, repo.getId(), run.getId())) {
                    ctx.findingRepo().save(f); count++;
                }
                run.setStatus(PipelineStatus.COMPLETE);
                run.setFindingsCount(count);
            } catch (ScannerException e) {
                run.setStatus(PipelineStatus.FAILED);
                run.setCompletedAt(LocalDateTime.now());
                ctx.scanRunRepo().update(run);
                throw e;
            }
            run.setCompletedAt(LocalDateTime.now());
            ctx.scanRunRepo().update(run);
        }

        if (cancelRequested.get()) return;

        if (choice.trivy()) {
            TrivyAdapter trivy = new TrivyAdapter();
            if (trivy.isAvailable()) {
                Platform.runLater(() -> setStatus(prefix + "Running Trivy…"));
                ScanRun run = new ScanRun(repo.getId(), com.vulntriage.domain.enums.ScannerType.TRIVY);
                ctx.scanRunRepo().save(run);
                try {
                    List<RawFinding> raw = trivy.scan(repo.getLocalPath(), ScanConfig.trivyOnly());
                    int count = 0;
                    for (Finding f : normaliser.normaliseAll(raw, repo.getId(), run.getId())) {
                        ctx.findingRepo().save(f); count++;
                    }
                    run.setStatus(PipelineStatus.COMPLETE);
                    run.setFindingsCount(count);
                } catch (Exception e) { run.setStatus(PipelineStatus.FAILED); }
                run.setCompletedAt(LocalDateTime.now());
                ctx.scanRunRepo().update(run);
            }
        }

        if (cancelRequested.get()) return;

        if (choice.gitleaks()) {
            GitleaksAdapter gitleaks = new GitleaksAdapter();
            if (gitleaks.isAvailable()) {
                Platform.runLater(() -> setStatus(prefix + "Running Gitleaks…"));
                ScanRun run = new ScanRun(repo.getId(), com.vulntriage.domain.enums.ScannerType.GITLEAKS);
                ctx.scanRunRepo().save(run);
                try {
                    List<RawFinding> raw = gitleaks.scan(repo.getLocalPath(), ScanConfig.defaults());
                    int count = 0;
                    for (Finding f : normaliser.normaliseAll(raw, repo.getId(), run.getId())) {
                        ctx.findingRepo().save(f); count++;
                    }
                    run.setStatus(PipelineStatus.COMPLETE);
                    run.setFindingsCount(count);
                } catch (Exception e) { run.setStatus(PipelineStatus.FAILED); }
                run.setCompletedAt(LocalDateTime.now());
                ctx.scanRunRepo().update(run);
            }
        }

        if (cancelRequested.get()) return;

        if (choice.codeql()) {
            CodeQLAdapter codeql = new CodeQLAdapter();
            if (codeql.isAvailable()) {
                Platform.runLater(() -> setStatus(prefix + "Running CodeQL (may take several minutes)…"));
                ScanRun run = new ScanRun(repo.getId(), com.vulntriage.domain.enums.ScannerType.CODEQL);
                ctx.scanRunRepo().save(run);
                try {
                    List<RawFinding> raw = codeql.scan(repo.getLocalPath(), ScanConfig.defaults());
                    int count = 0;
                    for (Finding f : normaliser.normaliseAll(raw, repo.getId(), run.getId())) {
                        ctx.findingRepo().save(f); count++;
                    }
                    run.setStatus(PipelineStatus.COMPLETE);
                    run.setFindingsCount(count);
                } catch (Exception e) { run.setStatus(PipelineStatus.FAILED); }
                run.setCompletedAt(LocalDateTime.now());
                ctx.scanRunRepo().update(run);
            }
        }

        if (cancelRequested.get()) return;

        if (choice.sonarqube()) {
            SonarQubeAdapter sonar = new SonarQubeAdapter(choice.sonarUrl(), choice.sonarToken());
            if (sonar.isAvailable()) {
                Platform.runLater(() -> setStatus(prefix + "Running SonarQube…"));
                ScanRun run = new ScanRun(repo.getId(), com.vulntriage.domain.enums.ScannerType.SONARQUBE);
                ctx.scanRunRepo().save(run);
                try {
                    List<RawFinding> raw = sonar.scan(repo.getLocalPath(), ScanConfig.defaults());
                    int count = 0;
                    for (Finding f : normaliser.normaliseAll(raw, repo.getId(), run.getId())) {
                        ctx.findingRepo().save(f); count++;
                    }
                    run.setStatus(PipelineStatus.COMPLETE);
                    run.setFindingsCount(count);
                    final int savedCount = count;
                    Platform.runLater(() -> setStatus(prefix + "SonarQube: " + savedCount + " findings saved."));
                } catch (Exception e) {
                    run.setStatus(PipelineStatus.FAILED);
                    Platform.runLater(() -> setStatus(prefix + "SonarQube failed: " + e.getMessage()));
                }
                run.setCompletedAt(LocalDateTime.now());
                ctx.scanRunRepo().update(run);
            }
        }

        repo.setLastScanned(LocalDateTime.now());
        ctx.repositoryRepo().update(repo);
    }

    // ── Remove ─────────────────────────────────────────────────────────────

    private void removeSelected() {
        RepositoryRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setStatus("Select a repository to remove.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove Repository");
        confirm.setHeaderText("Remove \"" + selected.getName() + "\" from the list?");
        confirm.setContentText(
            "The repository will be removed from this list. "
            + "All its findings, reviews, and scan history will be kept.");
        confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                ctx.repositoryRepo().delete(selected.getId());
                refresh();
                setStatus("\"" + selected.getName() + "\" removed from list. Findings preserved.");
            }
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void refresh() {
        rows.clear();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");
        ctx.repositoryRepo().findAll().forEach(r -> {
            String lastScanned = r.getLastScanned() != null
                ? r.getLastScanned().format(fmt) : "Never scanned";
            long count = ctx.findingRepo().findByRepositoryId(r.getId()).size();
            rows.add(new RepositoryRow(r.getId(), r.getName(),
                r.getLocalPath(), lastScanned, String.valueOf(count)));
        });
    }

    private void setScanningState(boolean scanning, String message) {
        progressBar.setVisible(scanning);
        setStatus(message);
        table.setDisable(scanning);
        stopBtn.setVisible(scanning);
        stopBtn.setManaged(scanning);
        stopBtn.setDisable(false);
        if (scanning) {
            scanBtn.setText("⏳  Scanning…");
            scanBtn.setDisable(true);
            scanBtn.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: #1D4ED8; "
                + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 7 16; "
                + "-fx-border-color: #BFDBFE; -fx-border-radius: 6px;");
        } else {
            scanBtn.setText("▶  Scan Selected");
            scanBtn.setDisable(false);
            scanBtn.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: #1D4ED8; "
                + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 7 16; "
                + "-fx-border-color: #BFDBFE; -fx-border-radius: 6px;");
        }
    }

    private void setStatus(String msg) {
        statusLabel.setText(msg);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Info");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private <T> TableColumn<RepositoryRow, T> col(String title, String property, int w) {
        TableColumn<RepositoryRow, T> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        col.setPrefWidth(w);
        return col;
    }

    private Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: #374151;");
        return l;
    }

    private Button primaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + ACCENT + "; -fx-text-fill: white; "
            + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 7 16;");
        return b;
    }

    private Button secondaryBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: " + ACCENT + "; "
            + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 7 16; "
            + "-fx-border-color: #BFDBFE; -fx-border-radius: 6px;");
        return b;
    }

    private Button dangerBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: #FEF2F2; -fx-text-fill: #DC2626; "
            + "-fx-background-radius: 6px; -fx-font-size: 12px; -fx-padding: 7 16; "
            + "-fx-border-color: #FECACA; -fx-border-radius: 6px;");
        return b;
    }

    // ── Row model ──────────────────────────────────────────────────────────

    public static class RepositoryRow {
        private final long   id;
        private final String name;
        private final String localPath;
        private final String lastScanned;
        private final String findingCount;

        public RepositoryRow(long id, String name, String localPath,
                             String lastScanned, String findingCount) {
            this.id           = id;
            this.name         = name;
            this.localPath    = localPath;
            this.lastScanned  = lastScanned;
            this.findingCount = findingCount;
        }

        public long   getId()           { return id; }
        public String getName()         { return name; }
        public String getLocalPath()    { return localPath; }
        public String getLastScanned()  { return lastScanned; }
        public String getFindingCount() { return findingCount; }
    }
}
