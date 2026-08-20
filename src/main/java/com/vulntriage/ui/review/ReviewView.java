package com.vulntriage.ui.review;

import com.vulntriage.app.AppContext;
import com.vulntriage.ui.UIUtils;
import com.vulntriage.command.AssignVerdictCommand;
import com.vulntriage.command.CommandHistory;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.ManualReview;
import com.vulntriage.domain.enums.Verdict;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static com.vulntriage.config.ThemeColors.*;

/**
 * Manual Review screen — the core thesis data collection tool.
 *
 * Shows one finding at a time. The analyst assigns a verdict using
 * keyboard shortcuts and moves to the next finding automatically.
 *
 * Keyboard shortcuts:
 *   T — True Positive (genuine vulnerability)
 *   F — False Positive (safe, flagged incorrectly)
 *   R — Review (uncertain, needs more context)
 *   ← / Backspace — go to previous finding
 *   → — go to next finding without assigning
 *   Escape — clear focus from notes field back to main view
 *
 * At 20-30 findings per minute using keyboard shortcuts,
 * 1000 findings takes approximately 35-50 minutes.
 */
public class ReviewView {

    // ── Palette ────────────────────────────────────────────────────────────
    private static final String MONO     = "Courier New";

    private final AppContext ctx = AppContext.getInstance();

    // ── State ──────────────────────────────────────────────────────────────
    private List<Finding> findings  = new ArrayList<>();
    private int           index     = 0;
    private final CommandHistory history = new CommandHistory();

    // ── UI references ──────────────────────────────────────────────────────
    private Label   positionLabel;
    private Label   progressLabel;
    private ProgressBar progressBar;
    private Label   severityBadge;
    private Label   categoryLabel;
    private Label   scannerBadge;
    private Label   ruleLabel;
    private Label   cvssLabel;
    private Label   fileLabel;
    private Label   messageLabel;
    private TextArea codeArea;
    private TextArea notesField;
    private Label   currentVerdictLabel;
    private Button  tpBtn, fpBtn, revBtn;
    private VBox    root;
    private String  currentFilePath;

    public Node build() {
        loadFindings();

        root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");
        root.setFocusTraversable(true);

        root.getChildren().addAll(
            buildHeader(),
            buildBody()
        );

        // ── Keyboard shortcuts ─────────────────────────────────────────────
        root.setOnKeyPressed(event -> {
            if (notesField != null && notesField.isFocused()) {
                // Only intercept Escape from the notes field
                if (event.getCode() == KeyCode.ESCAPE) {
                    root.requestFocus();
                    event.consume();
                }
                return;
            }
            switch (event.getCode()) {
                case T -> { assignVerdict(Verdict.TP);     event.consume(); }
                case F -> { assignVerdict(Verdict.FP);     event.consume(); }
                case R -> { assignVerdict(Verdict.REVIEW); event.consume(); }
                case RIGHT            -> { moveNext();  event.consume(); }
                case LEFT, BACK_SPACE -> { movePrev();  event.consume(); }
                case Z -> {
                    if (event.isControlDown()) { undoLastVerdict(); event.consume(); }
                }
                default -> {}
            }
        });

        // Focus the root so keyboard events are received immediately
        javafx.application.Platform.runLater(root::requestFocus);

        if (!findings.isEmpty()) showFinding(findFirstUnreviewedIndex());

        return root;
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox(16);
        header.setPadding(new Insets(18, 28, 14, 28));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 1 0;");

        VBox titleBlock = new VBox(2);
        Label title = new Label("Manual Review");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label(
            "Press  T = True Positive   F = False Positive   R = Review   ← = Back   → = Skip");
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: '" + MONO + "';");
        titleBlock.getChildren().addAll(title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        positionLabel = new Label("#0 of 0");
        positionLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + BLUE + "; -fx-font-family: '" + MONO + "';");

        progressLabel = new Label("0 / 0 reviewed");
        progressLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: '" + MONO + "';");

        VBox counter = new VBox(2);
        counter.setAlignment(Pos.CENTER_RIGHT);
        counter.getChildren().addAll(positionLabel, progressLabel);

        header.getChildren().addAll(titleBlock, spacer, counter);
        return header;
    }

    // ── Body ───────────────────────────────────────────────────────────────

    private SplitPane buildBody() {
        SplitPane split = new SplitPane(buildFindingPanel(), buildVerdictPanel());
        split.setDividerPositions(0.65);
        VBox.setVgrow(split, Priority.ALWAYS);
        split.setStyle("-fx-background-color: " + BG + ";");
        return split;
    }

    // ── Finding panel (left) ───────────────────────────────────────────────

    private VBox buildFindingPanel() {
        VBox panel = new VBox(14);
        panel.setPadding(new Insets(20, 20, 20, 28));
        panel.setStyle("-fx-background-color: " + BG + ";");

        // Progress bar
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(6);
        progressBar.setStyle("-fx-accent: " + BLUE + ";");

        // Metadata row
        HBox meta = new HBox(10);
        meta.setAlignment(Pos.CENTER_LEFT);
        severityBadge  = new Label("WARNING");
        categoryLabel  = new Label("xss");
        categoryLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        scannerBadge   = new Label("SEMGREP");
        scannerBadge.setStyle("-fx-background-color: " + PURPLE_BG + "; -fx-text-fill: " + PURPLE_DIM + "; "
            + "-fx-background-radius: 10; -fx-padding: 2 8 2 8; "
            + "-fx-font-size: 10px; -fx-font-weight: bold;");
        meta.getChildren().addAll(severityBadge, categoryLabel, scannerBadge);

        // Rule ID
        ruleLabel = new Label();
        ruleLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: '" + MONO + "';");
        ruleLabel.setWrapText(true);

        // CVSS score (visible only for findings that carry a vector)
        cvssLabel = new Label();
        cvssLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        cvssLabel.setVisible(false);
        cvssLabel.setManaged(false);

        // File + line
        fileLabel = new Label();
        fileLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: '" + MONO + "';");
        fileLabel.setWrapText(true);

        // Message
        messageLabel = new Label();
        messageLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + TEXT + "; "
            + "-fx-font-weight: bold;");
        messageLabel.setWrapText(true);

        // Code snippet
        Label codeHeading = sectionHeading("Code Snippet");
        Label expandHint = new Label("⤢ double-click to view full file");
        expandHint.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        HBox codeHeader = new HBox(8, codeHeading, expandHint);
        codeHeader.setAlignment(Pos.CENTER_LEFT);
        codeArea = new TextArea();
        codeArea.setEditable(false);
        codeArea.setPrefRowCount(12);
        codeArea.getStyleClass().add("code-snippet");
        codeArea.setStyle(
            "-fx-font-family: '" + MONO + "'; -fx-font-size: 12px; "
            + "-fx-text-fill: #CDD6F4; "
            + "-fx-border-color: #313244; -fx-border-radius: 6px; "
            + "-fx-background-radius: 6px;");
        codeArea.setFocusTraversable(false); // don't steal focus from root
        codeArea.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && currentFilePath != null) showFullFile();
        });

        // Notes
        Label notesHeading = sectionHeading("Notes (optional)");
        notesField = new TextArea();
        notesField.setPromptText("Add notes about this finding… (press Escape when done)");
        notesField.setPrefRowCount(3);
        notesField.setStyle(
            "-fx-font-size: 12px; -fx-border-color: " + BORDER + "; "
            + "-fx-border-radius: 6px; -fx-background-radius: 6px;");

        panel.getChildren().addAll(
            progressBar, meta, ruleLabel, cvssLabel, fileLabel, messageLabel,
            codeHeader, codeArea, notesHeading, notesField
        );
        return panel;
    }

    // ── Verdict panel (right) ──────────────────────────────────────────────

    private VBox buildVerdictPanel() {
        VBox panel = new VBox(16);
        panel.setPadding(new Insets(24, 24, 24, 20));
        panel.setAlignment(Pos.TOP_CENTER);
        panel.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 0 1;");

        Label heading = new Label("Assign Verdict");
        heading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        // Current verdict display
        currentVerdictLabel = new Label("Not reviewed");
        currentVerdictLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");

        Label divider = new Label("─────────────────");
        divider.setStyle("-fx-text-fill: " + BORDER + ";");

        // Verdict buttons
        tpBtn  = verdictBtn("[T]  True Positive",  GREEN, "Genuine exploitable vulnerability");
        fpBtn  = verdictBtn("[F]  False Positive",  RED,   "Safe — rule fired incorrectly");
        revBtn = verdictBtn("[R]  Needs Review",    AMBER, "Uncertain — need more context");

        tpBtn .setOnAction(e -> { assignVerdict(Verdict.TP);     root.requestFocus(); });
        fpBtn .setOnAction(e -> { assignVerdict(Verdict.FP);     root.requestFocus(); });
        revBtn.setOnAction(e -> { assignVerdict(Verdict.REVIEW); root.requestFocus(); });

        // Clear verdict button
        Button clearBtn = new Button("✕  Clear Verdict");
        clearBtn.setMaxWidth(Double.MAX_VALUE);
        clearBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + MUTED + "; "
            + "-fx-background-radius: 8; -fx-padding: 7 16; -fx-font-size: 12px; -fx-cursor: hand; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 8;");
        clearBtn.setOnMouseEntered(e -> clearBtn.setStyle(
            "-fx-background-color: " + NEUTRAL_BG + "; -fx-text-fill: " + TEXT + "; "
            + "-fx-background-radius: 8; -fx-padding: 7 16; -fx-font-size: 12px; -fx-cursor: hand; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 8;"));
        clearBtn.setOnMouseExited(e -> clearBtn.setStyle(
            "-fx-background-color: transparent; -fx-text-fill: " + MUTED + "; "
            + "-fx-background-radius: 8; -fx-padding: 7 16; -fx-font-size: 12px; -fx-cursor: hand; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 8;"));
        clearBtn.setOnAction(e -> {
            if (findings.isEmpty()) return;
            Finding cf = findings.get(index);
            ctx.reviewRepo().deleteByFindingId(cf.getId());
            setCurrentVerdictLabel(null);
            showFinding(index);
            root.requestFocus();
        });

        // Navigation buttons
        HBox navRow = new HBox(8);
        navRow.setAlignment(Pos.CENTER);
        Button prevBtn = navBtn("← Back");
        Button nextBtn = navBtn("Skip →");
        prevBtn.setOnAction(e -> { movePrev(); root.requestFocus(); });
        nextBtn.setOnAction(e -> { moveNext(); root.requestFocus(); });

        // Jump-to field
        javafx.scene.control.TextField jumpField = new javafx.scene.control.TextField();
        jumpField.setPromptText("# go to");
        jumpField.setPrefWidth(80);
        jumpField.setStyle("-fx-font-size: 12px; -fx-border-color: " + BORDER + "; "
            + "-fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 5 8;");
        jumpField.setOnAction(e -> {
            try {
                int num = Integer.parseInt(jumpField.getText().trim());
                if (num >= 1 && num <= findings.size()) {
                    showFinding(num - 1); // convert 1-based to 0-based index
                    jumpField.clear();
                    root.requestFocus();
                } else {
                    jumpField.setStyle("-fx-border-color: #DC2626; -fx-font-size: 12px; "
                        + "-fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 5 8;");
                }
            } catch (NumberFormatException ex) {
                jumpField.setStyle("-fx-border-color: #DC2626; -fx-font-size: 12px; "
                    + "-fx-border-radius: 5; -fx-background-radius: 5; -fx-padding:5 8;");
            }
        });
        // Reset border on typing
        jumpField.textProperty().addListener((o, old, n) ->
            jumpField.setStyle("-fx-font-size: 12px; -fx-border-color: " + BORDER + "; "
                + "-fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 5 8;"));

        navRow.getChildren().addAll(prevBtn, nextBtn, jumpField);

        // Stats
        Label statsHeading = sectionHeading("Session Stats");
        Label tpStat  = buildStat("True Positives",  GREEN);
        Label fpStat  = buildStat("False Positives", RED);
        Label revStat = buildStat("Review",          AMBER);

        panel.getChildren().addAll(
            heading, currentVerdictLabel, divider,
            tpBtn, fpBtn, revBtn, clearBtn,
            navRow, new Separator(),
            statsHeading, tpStat, fpStat, revStat
        );
        return panel;
    }

    // ── Navigation & data ──────────────────────────────────────────────────

    private int findFirstUnreviewedIndex() {
        for (int i = 0; i < findings.size(); i++) {
            if (ctx.reviewRepo().findByFindingId(findings.get(i).getId()).isEmpty()) {
                return i;
            }
        }
        return 0; // all reviewed — go to start
    }

    private void loadFindings() {
        findings.clear();
        ctx.repositoryRepo().findAll().forEach(repo ->
            findings.addAll(ctx.findingRepo().findByRepositoryId(repo.getId()))
        );
    }

    private void showFinding(int i) {
        if (findings.isEmpty()) {
            messageLabel.setText("No findings loaded. Run a scan from the Repositories screen first.");
            return;
        }

        index = Math.max(0, Math.min(i, findings.size() - 1));
        Finding f = findings.get(index);

        // Position counter
        positionLabel.setText("#" + (index + 1) + " of " + findings.size());

        // Progress — scoped to the findings currently in this view
        List<Long> findingIds = findings.stream().map(Finding::getId).toList();
        long reviewed = ctx.reviewRepo().countForFindingIds(findingIds);
        long total    = findings.size();
        progressLabel.setText(reviewed + " / " + total + " reviewed");
        progressBar.setProgress(total > 0 ? (double) reviewed / total : 0);

        // Metadata
        applySeverityStyle(f.getSeverity() != null ? f.getSeverity().name() : "INFO");
        categoryLabel.setText(f.getCategory() != null ? f.getCategory() : "");
        if (f.getSource() != null) {
            String sname = f.getSource().name();
            String[] scannerColors = switch (sname) {
                case "SEMGREP"   -> new String[]{"" + PURPLE_BG + "", "" + PURPLE_DIM + ""};
                case "TRIVY"     -> new String[]{"" + BLUE_BG + "", " + BLUE + "};
                case "GITLEAKS"  -> new String[]{"" + AMBER_BG + "", "" + AMBER_DIM + ""};
                case "CODEQL"    -> new String[]{"" + GREEN_BG + "", "" + GREEN_DIM + ""};
                case "SONARQUBE" -> new String[]{"" + RED_BG + "", "" + RED_DIM + ""};
                default          -> new String[]{"" + NEUTRAL_BG + "", "" + DIM + ""};
            };
            scannerBadge.setText(sname);
            scannerBadge.setStyle("-fx-background-color: " + scannerColors[0] + "; "
                + "-fx-text-fill: " + scannerColors[1] + "; "
                + "-fx-background-radius: 10; -fx-padding: 2 8 2 8; "
                + "-fx-font-size: 10px; -fx-font-weight: bold;");
            scannerBadge.setVisible(true);
        } else {
            scannerBadge.setVisible(false);
        }
        ruleLabel.setText("Rule: " + (f.getRuleId() != null ? f.getRuleId() : "—"));
        if (f.getCvssScore() != null) {
            double s = f.getCvssScore();
            String sev = com.vulntriage.cvss.CvssCalculator.severity(s);
            String color = s >= 9.0 ? RED : s >= 7.0 ? AMBER : s >= 4.0 ? BLUE : MUTED;
            cvssLabel.setText(String.format("CVSS v3.1  %.1f  (%s)", s, sev));
            cvssLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
            cvssLabel.setVisible(true);
            cvssLabel.setManaged(true);
        } else {
            cvssLabel.setVisible(false);
            cvssLabel.setManaged(false);
        }
        fileLabel.setText("File: " + (f.getFilePath() != null ? f.getFilePath() : "—")
            + (f.getLineNumber() != null ? "  :  line " + f.getLineNumber() : ""));
        messageLabel.setText(f.getMessage() != null ? f.getMessage() : "");
        codeArea.setText(f.getCodeSnippet() != null ? f.getCodeSnippet() : "(no code snippet)");
        currentFilePath = f.getFilePath();

        // Existing verdict (if re-visiting)
        Optional<ManualReview> existing = ctx.reviewRepo().findByFindingId(f.getId());
        if (existing.isPresent()) {
            notesField.setText(existing.get().getNotes() != null ? existing.get().getNotes() : "");
            setCurrentVerdictLabel(existing.get().getVerdict());
        } else {
            notesField.setText("");
            setCurrentVerdictLabel(null);
        }
    }

    private void assignVerdict(Verdict verdict) {
        if (findings.isEmpty()) return;
        Finding f = findings.get(index);

        // Capture previous state for undo
        Optional<ManualReview> prior = ctx.reviewRepo().findByFindingId(f.getId());
        Verdict prevVerdict = prior.map(ManualReview::getVerdict).orElse(null);
        String  prevNotes   = prior.map(ManualReview::getNotes).orElse(null);

        AssignVerdictCommand cmd = new AssignVerdictCommand(
            ctx.reviewRepo(), f.getId(), verdict, notesField.getText().trim(),
            prevVerdict, prevNotes
        );
        history.executeAndPush(cmd);

        setCurrentVerdictLabel(verdict);
        moveToNextUnreviewed();
    }

    private void undoLastVerdict() {
        if (!history.undo()) return;
        // Refresh current finding display to reflect restored state
        showFinding(index);
    }

    private void moveToNextUnreviewed() {
        // Try to find the next unreviewed finding after current index
        for (int i = index + 1; i < findings.size(); i++) {
            if (ctx.reviewRepo().findByFindingId(findings.get(i).getId()).isEmpty()) {
                showFinding(i);
                return;
            }
        }
        // If none found after, just advance by 1 (or stay at end)
        if (index < findings.size() - 1) {
            showFinding(index + 1);
        } else {
            // All reviewed
            positionLabel.setText("#" + findings.size() + " of " + findings.size());
            progressLabel.setText(findings.size() + " / " + findings.size() + " reviewed ✓");
            progressBar.setProgress(1.0);
        }
    }

    private void moveNext() {
        if (index < findings.size() - 1) showFinding(index + 1);
    }

    private void movePrev() {
        if (index > 0) showFinding(index - 1);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void applySeverityStyle(String severity) {
        String bg, fg;
        switch (severity) {
            case "ERROR"   -> { bg = "" + RED_BG + ""; fg = "" + RED_DIM + ""; }
            case "WARNING" -> { bg = "" + AMBER_BG + ""; fg = "" + AMBER_DIM + ""; }
            default        -> { bg = "" + BTN_BG_SECONDARY + ""; fg = "" + BTN_FG_SECONDARY + ""; }
        }
        severityBadge.setText(severity);
        severityBadge.setStyle(
            "-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; "
            + "-fx-background-radius: 10; -fx-padding: 2 8 2 8; "
            + "-fx-font-size: 11px; -fx-font-weight: bold;");
    }

    private void setCurrentVerdictLabel(Verdict v) {
        if (v == null) {
            currentVerdictLabel.setText("Not reviewed");
            currentVerdictLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        } else {
            String color = switch (v) {
                case TP -> GREEN; case FP -> RED; default -> AMBER;
            };
            currentVerdictLabel.setText("Current: " + v.name());
            currentVerdictLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; "
                + "-fx-text-fill: " + color + ";");
        }
    }

    private Button verdictBtn(String text, String color, String tooltip) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setPrefHeight(52);
        b.setStyle("-fx-background-color: " + CARD + "; -fx-text-fill: " + color + "; "
            + "-fx-font-size: 13px; -fx-font-weight: bold; -fx-background-radius: 8; "
            + "-fx-border-color: " + color + "; -fx-border-radius: 8; "
            + "-fx-border-width: 1.5; -fx-font-family: '" + MONO + "';");
        b.setTooltip(new Tooltip(tooltip));
        b.setFocusTraversable(false);
        b.setOnMouseEntered(e -> b.setStyle(
            "-fx-background-color: " + color + "; -fx-text-fill: white; "
            + "-fx-font-size: 13px; -fx-font-weight: bold; -fx-background-radius: 8; "
            + "-fx-border-color: " + color + "; -fx-border-radius: 8; "
            + "-fx-border-width: 1.5; -fx-font-family: '" + MONO + "';"));
        b.setOnMouseExited(e -> b.setStyle(
            "-fx-background-color: " + CARD + "; -fx-text-fill: " + color + "; "
            + "-fx-font-size: 13px; -fx-font-weight: bold; -fx-background-radius: 8; "
            + "-fx-border-color: " + color + "; -fx-border-radius: 8; "
            + "-fx-border-width: 1.5; -fx-font-family: '" + MONO + "';"));
        return b;
    }

    private Button navBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + DIM + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 6 16; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6;");
        b.setFocusTraversable(false);
        return b;
    }

    private void showFullFile() {
        String content;
        try {
            content = Files.readString(Paths.get(currentFilePath));
        } catch (Exception ex) {
            // Fall back to the stored code snippet when the file isn't on disk
            Finding f = !findings.isEmpty() ? findings.get(index) : null;
            String snippet = f != null ? f.getCodeSnippet() : null;
            content = (snippet != null && !snippet.isBlank())
                ? snippet
                : "(Could not read file: " + currentFilePath + ")";
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Full File");
        dialog.setHeaderText(currentFilePath);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(800);
        dialog.getDialogPane().setPrefHeight(640);
        UIUtils.applyTheme(dialog);

        TextArea fileArea = new TextArea(content);
        fileArea.setEditable(false);
        fileArea.setWrapText(false);
        fileArea.getStyleClass().add("code-snippet");
        fileArea.setStyle("-fx-font-family: '" + MONO + "'; -fx-font-size: 12px; -fx-text-fill: #CDD6F4;");

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

    private Label sectionHeading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; "
            + "-fx-text-fill: #9CA3AF; -fx-letter-spacing: 0.5px;");
        return l;
    }

    private Label buildStat(String label, String color) {
        Label l = new Label();
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: " + color + ";");
        // Will be populated by refresh — placeholder for now
        l.setText(label + ": —");
        return l;
    }
}