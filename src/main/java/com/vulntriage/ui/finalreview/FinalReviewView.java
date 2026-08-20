package com.vulntriage.ui.finalreview;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.FinalReview;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.enums.FinalVerdict;
import com.vulntriage.domain.enums.Severity;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.input.KeyCode;

import java.util.List;
import java.util.Optional;
import java.io.File;
import java.nio.file.Files;
import static com.vulntriage.config.ThemeColors.*;

public class FinalReviewView {

    private static final String MONO = "Courier New";

    private final AppContext ctx = AppContext.getInstance();

    private List<Finding>  cases;
    private int            selectedIndex = 0;

    private VBox           caseList;
    private ScrollPane     caseListScroll;
    private Label          headerCountLabel;
    private Label          progressLabel;
    private ProgressBar    progressBar;

    // Detail panel refs
    private Label          severityBadge;
    private Label          categoryLabel;
    private Label          scannerBadge;
    private Label          ruleLabel;
    private Label          fileLabel;
    private Label          messageLabel;
    private TextArea       codeArea;
    private VBox           llmPanel;
    private TextArea       notesField;
    private Label          currentVerdictLabel;
    private Button         fixedBtn;
    private Button         wontFixBtn;
    private Button         deferBtn;
    private VBox           detailRoot;
    private Label          emptyLabel;

    public Node build() {
        cases = ctx.finalReviewRepo().findQualifyingFindings();

        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");
        root.setFocusTraversable(true);

        root.getChildren().addAll(buildHeader(), buildBody());

        root.setOnKeyPressed(e -> {
            if (notesField != null && notesField.isFocused()) {
                if (e.getCode() == KeyCode.ESCAPE) { root.requestFocus(); e.consume(); }
                return;
            }
            switch (e.getCode()) {
                case F -> { applyVerdict(FinalVerdict.FIXED);     e.consume(); }
                case W -> { applyVerdict(FinalVerdict.WONT_FIX);  e.consume(); }
                case D -> { applyVerdict(FinalVerdict.DEFERRED);  e.consume(); }
                case DOWN, RIGHT -> { moveNext(); e.consume(); }
                case UP,   LEFT  -> { movePrev(); e.consume(); }
                default -> {}
            }
        });

        javafx.application.Platform.runLater(root::requestFocus);
        if (!cases.isEmpty()) showCase(0);

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
        Label title = new Label("Case Review");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        Label sub = new Label(
            "F = Fixed   W = Won't Fix   D = Defer   ↑ / ↓ = Previous / Next case");
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: '" + MONO + "';");
        titleBlock.getChildren().addAll(title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        headerCountLabel = new Label(cases.size() + " cases");
        headerCountLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + BLUE + "; -fx-font-family: '" + MONO + "';");

        progressLabel = new Label("0 / " + cases.size() + " reviewed");
        progressLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: '" + MONO + "';");

        VBox counter = new VBox(2);
        counter.setAlignment(Pos.CENTER_RIGHT);
        counter.getChildren().addAll(headerCountLabel, progressLabel);

        header.getChildren().addAll(titleBlock, spacer, counter);
        return header;
    }

    // ── Body ───────────────────────────────────────────────────────────────

    private SplitPane buildBody() {
        SplitPane split = new SplitPane(buildCaseListPanel(), buildDetailPanel());
        split.setDividerPositions(0.32);
        VBox.setVgrow(split, Priority.ALWAYS);
        split.setStyle("-fx-background-color: " + BG + ";");
        return split;
    }

    // ── Case list (left) ───────────────────────────────────────────────────

    private VBox buildCaseListPanel() {
        VBox panel = new VBox(0);
        panel.setStyle("-fx-background-color: " + SURFACE + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 1 0 0;");

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(5);
        progressBar.setStyle("-fx-accent: " + GREEN + ";");

        Label listHeader = new Label("CONFIRMED TRUE POSITIVES");
        listHeader.setPadding(new Insets(10, 14, 8, 14));
        listHeader.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: " + DIM + ";");

        caseList = new VBox(0);

        caseListScroll = new ScrollPane(caseList);
        caseListScroll.setFitToWidth(true);
        caseListScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        caseListScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(caseListScroll, Priority.ALWAYS);

        // Intercept UP/DOWN on the scroll pane so they navigate cases, not scroll content
        caseListScroll.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.UP)   { movePrev(); e.consume(); }
            if (e.getCode() == KeyCode.DOWN)  { moveNext(); e.consume(); }
        });

        panel.getChildren().addAll(progressBar, listHeader, caseListScroll);
        rebuildCaseList();
        return panel;
    }

    private void rebuildCaseList() {
        caseList.getChildren().clear();
        if (cases.isEmpty()) {
            Label empty = new Label("No qualifying cases.\n\nCases appear here when a finding\nhas manual verdict TP and at least\n2 LLM versions also triaged it TP.");
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
            empty.setPadding(new Insets(24, 14, 0, 14));
            empty.setWrapText(true);
            caseList.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < cases.size(); i++) {
            caseList.getChildren().add(buildCaseRow(i));
        }
        updateProgress();
    }

    private HBox buildCaseRow(int idx) {
        Finding f = cases.get(idx);
        Optional<FinalReview> existing = ctx.finalReviewRepo().findByFindingId(f.getId());

        HBox row = new HBox(10);
        row.setPadding(new Insets(10, 14, 10, 14));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle(rowStyle(idx, false));
        row.setOnMouseClicked(e -> showCase(idx));
        row.setOnMouseEntered(e -> row.setStyle(rowStyle(idx, true)));
        row.setOnMouseExited (e -> row.setStyle(rowStyle(idx, false)));

        VBox text = new VBox(3);
        HBox.setHgrow(text, Priority.ALWAYS);

        String shortRule = f.getRuleId().contains(".")
            ? f.getRuleId().substring(f.getRuleId().lastIndexOf('.') + 1)
            : f.getRuleId();
        Label ruleL = new Label(shortRule);
        ruleL.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + "; "
            + "-fx-font-family: '" + MONO + "';");
        ruleL.setMaxWidth(180);

        String fileName = f.getFilePath().contains("/")
            ? f.getFilePath().substring(f.getFilePath().lastIndexOf('/') + 1)
            : f.getFilePath().contains("\\")
                ? f.getFilePath().substring(f.getFilePath().lastIndexOf('\\') + 1)
                : f.getFilePath();
        Label fileL = new Label(fileName + (f.getLineNumber() != null ? ":" + f.getLineNumber() : ""));
        fileL.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + ";");

        text.getChildren().addAll(ruleL, fileL);

        Label statusChip = verdictChip(existing.map(FinalReview::getVerdict).orElse(null));

        row.getChildren().addAll(text, statusChip);
        return row;
    }

    private String rowStyle(int idx, boolean hover) {
        boolean selected = idx == selectedIndex;
        String bg = selected ? BLUE_BG : (hover ? NEUTRAL_BG : "transparent");
        return "-fx-background-color: " + bg + "; -fx-cursor: hand; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 1 0;";
    }

    private Label verdictChip(FinalVerdict v) {
        Label chip = new Label();
        if (v == null) {
            chip.setText("Pending");
            chip.setStyle("-fx-background-color: " + NEUTRAL_BG + "; -fx-text-fill: " + MUTED + "; "
                + "-fx-background-radius: 10; -fx-padding: 2 8; -fx-font-size: 10px;");
        } else switch (v) {
            case FIXED    -> { chip.setText("Fixed");      chip.setStyle("-fx-background-color: " + GREEN_BG + "; -fx-text-fill: " + GREEN_DIM + "; -fx-background-radius: 10; -fx-padding: 2 8; -fx-font-size: 10px;"); }
            case WONT_FIX -> { chip.setText("Won't Fix"); chip.setStyle("-fx-background-color: " + RED_BG + "; -fx-text-fill: " + RED_DIM + "; -fx-background-radius: 10; -fx-padding: 2 8; -fx-font-size: 10px;"); }
            case DEFERRED -> { chip.setText("Deferred");   chip.setStyle("-fx-background-color: " + AMBER_BG + "; -fx-text-fill: " + AMBER_DIM + "; -fx-background-radius: 10; -fx-padding: 2 8; -fx-font-size: 10px;"); }
        }
        return chip;
    }

    // ── Detail panel (right) ───────────────────────────────────────────────

    private StackPane buildDetailPanel() {
        detailRoot = new VBox(0);
        detailRoot.setStyle("-fx-background-color: " + BG + ";");

        emptyLabel = new Label("Select a case from the list");
        emptyLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: " + MUTED + ";");

        ScrollPane scroll = new ScrollPane(detailRoot);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: " + BG + ";");

        // Pass UP/DOWN through to navigate cases rather than scroll content
        scroll.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.UP)   { movePrev(); e.consume(); }
            if (e.getCode() == KeyCode.DOWN)  { moveNext(); e.consume(); }
        });

        StackPane wrapper = new StackPane(emptyLabel, scroll);
        wrapper.setStyle("-fx-background-color: " + BG + ";");

        if (cases.isEmpty()) {
            scroll.setVisible(false);
        } else {
            emptyLabel.setVisible(false);
        }

        return wrapper;
    }

    private void showCase(int idx) {
        if (cases.isEmpty()) return;
        selectedIndex = idx;
        Finding f = cases.get(idx);

        rebuildCaseList();  // refresh sidebar highlight + chips

        detailRoot.getChildren().clear();
        detailRoot.getChildren().addAll(
            buildMetaSection(f),
            buildCodeSection(f),
            buildLlmSection(f),
            buildActionSection(f)
        );

        headerCountLabel.setText((idx + 1) + " / " + cases.size());
    }

    // ── Finding metadata ───────────────────────────────────────────────────

    private VBox buildMetaSection(Finding f) {
        VBox section = new VBox(10);
        section.setPadding(new Insets(20, 28, 0, 28));

        // Severity + category + scanner row
        HBox badges = new HBox(10);
        badges.setAlignment(Pos.CENTER_LEFT);

        Label sev = severityBadge(f.getSeverity());
        Label cat = new Label(f.getCategory());
        cat.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        Label src = new Label(f.getSource().name());
        src.setStyle("-fx-background-color: " + PURPLE_BG + "; -fx-text-fill: " + PURPLE_DIM + "; "
            + "-fx-background-radius: 10; -fx-padding: 2 8; -fx-font-size: 10px; -fx-font-weight: bold;");
        badges.getChildren().addAll(sev, cat, src);

        Label rule = new Label(f.getRuleId());
        rule.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: '" + MONO + "';");
        rule.setWrapText(true);

        String loc = f.getFilePath() + (f.getLineNumber() != null ? "  ·  line " + f.getLineNumber() : "");
        Label file = new Label(loc);
        file.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT
            + "; -fx-font-family: '" + MONO + "';");
        file.setWrapText(true);

        Label msg = new Label(f.getMessage());
        msg.setStyle("-fx-font-size: 13px; -fx-text-fill: " + TEXT + ";");
        msg.setWrapText(true);

        section.getChildren().addAll(badges, rule, file, msg);
        return section;
    }

    // ── Code snippet ───────────────────────────────────────────────────────

    private VBox buildCodeSection(Finding f) {
        VBox section = new VBox(4);
        section.setPadding(new Insets(16, 28, 0, 28));

        if (f.getCodeSnippet() == null || f.getCodeSnippet().isBlank()) return section;

        HBox hdr = new HBox(10);
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label lbl = sectionLabel("CODE SNIPPET");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = new Label("double-click to view full file");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: " + MUTED + "; -fx-font-style: italic;");
        hdr.getChildren().addAll(lbl, spacer, hint);

        TextArea code = new TextArea(f.getCodeSnippet());
        code.setEditable(false);
        code.setWrapText(false);
        code.setPrefRowCount(Math.min(12, f.getCodeSnippet().split("\n").length + 1));
        code.getStyleClass().add("code-snippet");
        code.setStyle("-fx-font-family: '" + MONO + "'; -fx-font-size: 12px;");
        code.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                showFullFileDialog(f);
            }
        });

        section.getChildren().addAll(hdr, code);
        return section;
    }

    private void showFullFileDialog(Finding f) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("File Viewer");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefSize(900, 680);
        com.vulntriage.ui.UIUtils.applyTheme(dialog);

        // Try to read the full file; fall back to the snippet
        String fullContent = null;
        String filePath = f.getFilePath();
        java.io.File file = new java.io.File(filePath);
        if (file.exists() && file.isFile()) {
            try {
                fullContent = java.nio.file.Files.readString(file.toPath());
            } catch (Exception ignored) {}
        }

        String content = fullContent != null ? fullContent : f.getCodeSnippet();
        boolean isFullFile = fullContent != null;

        VBox wrapper = new VBox(8);
        wrapper.setPadding(new Insets(12));
        wrapper.setStyle("-fx-background-color: " + BG + ";");

        Label titleLabel = new Label(isFullFile ? filePath : "Snippet only — file not found on disk");
        titleLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (isFullFile ? MUTED : AMBER)
            + "; -fx-font-family: '" + MONO + "';");
        titleLabel.setWrapText(true);

        if (f.getLineNumber() != null && isFullFile) {
            Label lineHint = new Label("Flagged line: " + f.getLineNumber());
            lineHint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + BLUE + ";");
            wrapper.getChildren().addAll(titleLabel, lineHint);
        } else {
            wrapper.getChildren().add(titleLabel);
        }

        TextArea area = new TextArea(addLineNumbers(content));
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("code-snippet");
        area.setStyle("-fx-font-family: '" + MONO + "'; -fx-font-size: 12px;");
        VBox.setVgrow(area, Priority.ALWAYS);
        wrapper.getChildren().add(area);
        VBox.setVgrow(wrapper, Priority.ALWAYS);

        dialog.getDialogPane().setContent(wrapper);

        // Scroll to flagged line after dialog opens
        if (f.getLineNumber() != null && isFullFile) {
            int targetLine = f.getLineNumber();
            dialog.setOnShown(e -> javafx.application.Platform.runLater(() -> {
                String[] lines = content.split("\n");
                int total = lines.length;
                if (total > 0 && targetLine > 0) {
                    // Position caret at the flagged line, which also scrolls the viewport
                    int charPos = 0;
                    for (int i = 0; i < Math.min(targetLine - 1, lines.length); i++) {
                        charPos += lines[i].length() + 1 + String.valueOf(i + 1).length() + 2;
                    }
                    area.positionCaret(Math.min(charPos, area.getText().length()));
                    area.requestFocus();
                }
            }));
        }

        com.vulntriage.ui.UIUtils.fixCodeSnippetBackground(dialog, area);
        dialog.showAndWait();
    }

    private String addLineNumbers(String content) {
        if (content == null) return "";
        String[] lines = content.split("\n");
        int width = String.valueOf(lines.length).length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%" + width + "d  %s%n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    // ── LLM analysis ───────────────────────────────────────────────────────

    private VBox buildLlmSection(Finding f) {
        VBox section = new VBox(8);
        section.setPadding(new Insets(16, 28, 0, 28));

        Label lbl = sectionLabel("LLM ANALYSIS  (versions that confirmed TP)");
        section.getChildren().add(lbl);

        List<LlmResult> results = ctx.llmRepo().findAll().stream()
            .filter(r -> r.getFindingId() == f.getId()
                && r.getLlmVerdict() != null
                && r.getLlmVerdict().name().equals("TP"))
            .toList();

        if (results.isEmpty()) {
            Label none = new Label("No TP LLM results found.");
            none.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
            section.getChildren().add(none);
            return section;
        }

        for (LlmResult r : results) {
            VBox card = new VBox(6);
            card.setPadding(new Insets(12));
            card.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 8; "
                + "-fx-border-color: " + BORDER + "; -fx-border-radius: 8;");

            HBox cardHeader = new HBox(10);
            cardHeader.setAlignment(Pos.CENTER_LEFT);
            Label ver = new Label(r.getPromptVersion() != null ? r.getPromptVersion() : "unknown");
            ver.setStyle("-fx-background-color: " + BLUE_BG + "; -fx-text-fill: " + BLUE_EXTRA + "; "
                + "-fx-background-radius: 10; -fx-padding: 2 8; -fx-font-size: 10px; -fx-font-weight: bold;");
            Label conf = new Label("Confidence: " + r.getConfidence() + "%");
            conf.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
            Label model = new Label(r.getModelUsed() != null ? r.getModelUsed() : "");
            model.setStyle("-fx-font-size: 10px; -fx-text-fill: " + DIM + ";");
            cardHeader.getChildren().addAll(ver, conf, model);

            if (r.getReasoning() != null && !r.getReasoning().isBlank()) {
                Label reasoning = new Label(r.getReasoning());
                reasoning.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT + ";");
                reasoning.setWrapText(true);
                card.getChildren().addAll(cardHeader, reasoning);
            } else {
                card.getChildren().add(cardHeader);
            }

            if (r.getRemediation() != null && !r.getRemediation().isBlank()) {
                Label remLabel = new Label("Remediation:");
                remLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
                Label rem = new Label(r.getRemediation());
                rem.setStyle("-fx-font-size: 12px; -fx-text-fill: " + GREEN + ";");
                rem.setWrapText(true);
                card.getChildren().addAll(remLabel, rem);
            }

            section.getChildren().add(card);
        }
        return section;
    }

    // ── Action section ─────────────────────────────────────────────────────

    private VBox buildActionSection(Finding f) {
        VBox section = new VBox(12);
        section.setPadding(new Insets(20, 28, 28, 28));

        Optional<FinalReview> existing = ctx.finalReviewRepo().findByFindingId(f.getId());

        Label lbl = sectionLabel("FINAL VERDICT");

        currentVerdictLabel = new Label();
        updateCurrentVerdictLabel(existing.map(FinalReview::getVerdict).orElse(null));

        Label notesLbl = new Label("Notes (optional)");
        notesLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");

        notesField = new TextArea();
        notesField.setPromptText("Add notes about this case…");
        notesField.setPrefRowCount(3);
        notesField.setWrapText(true);
        notesField.setStyle("-fx-font-size: 12px; -fx-control-inner-background: " + INPUT_BG
            + "; -fx-text-fill: " + TEXT + "; -fx-border-color: " + BORDER + ";");
        existing.ifPresent(r -> notesField.setText(r.getNotes() != null ? r.getNotes() : ""));

        fixedBtn   = actionBtn("✓  Fixed",      GREEN,    GREEN_BG,   GREEN_DIM);
        wontFixBtn = actionBtn("✗  Won't Fix",  RED,      RED_BG,     RED_DIM);
        deferBtn   = actionBtn("⏸  Defer",       AMBER,    AMBER_BG,   AMBER_DIM);

        fixedBtn  .setOnAction(e -> applyVerdict(FinalVerdict.FIXED));
        wontFixBtn.setOnAction(e -> applyVerdict(FinalVerdict.WONT_FIX));
        deferBtn  .setOnAction(e -> applyVerdict(FinalVerdict.DEFERRED));

        HBox btnRow = new HBox(12, fixedBtn, wontFixBtn, deferBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: " + BORDER + ";");
        sep.setPadding(new Insets(8, 0, 0, 0));

        section.getChildren().addAll(sep, lbl, currentVerdictLabel, notesLbl, notesField, btnRow);
        return section;
    }

    // ── Verdict application ────────────────────────────────────────────────

    private void applyVerdict(FinalVerdict verdict) {
        if (cases.isEmpty()) return;
        Finding f = cases.get(selectedIndex);
        String notes = notesField != null ? notesField.getText() : "";

        Optional<FinalReview> existing = ctx.finalReviewRepo().findByFindingId(f.getId());
        if (existing.isPresent()) {
            FinalReview r = existing.get();
            r.setVerdict(verdict);
            r.setNotes(notes);
            ctx.finalReviewRepo().update(r);
        } else {
            ctx.finalReviewRepo().save(new FinalReview(f.getId(), verdict, notes));
        }

        updateCurrentVerdictLabel(verdict);
        rebuildCaseList();
        updateProgress();

        // Auto-advance to next unreviewed
        int next = findNextPending(selectedIndex);
        if (next >= 0) showCase(next);
    }

    private int findNextPending(int from) {
        for (int i = from + 1; i < cases.size(); i++) {
            if (ctx.finalReviewRepo().findByFindingId(cases.get(i).getId()).isEmpty()) return i;
        }
        for (int i = 0; i < from; i++) {
            if (ctx.finalReviewRepo().findByFindingId(cases.get(i).getId()).isEmpty()) return i;
        }
        return -1;
    }

    private void updateCurrentVerdictLabel(FinalVerdict v) {
        if (currentVerdictLabel == null) return;
        if (v == null) {
            currentVerdictLabel.setText("No verdict yet");
            currentVerdictLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + MUTED + ";");
        } else {
            String color = switch (v) {
                case FIXED    -> GREEN;
                case WONT_FIX -> RED;
                case DEFERRED -> AMBER;
            };
            currentVerdictLabel.setText("Current: " + v.getDisplayName());
            currentVerdictLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        }
    }

    private void updateProgress() {
        long reviewed = cases.stream()
            .filter(f -> ctx.finalReviewRepo().findByFindingId(f.getId()).isPresent())
            .count();
        int total = cases.size();
        if (progressLabel != null) progressLabel.setText(reviewed + " / " + total + " reviewed");
        if (progressBar != null)   progressBar.setProgress(total == 0 ? 0 : (double) reviewed / total);
    }

    private void moveNext() {
        if (!cases.isEmpty()) showCase((selectedIndex + 1) % cases.size());
    }

    private void movePrev() {
        if (!cases.isEmpty()) showCase((selectedIndex - 1 + cases.size()) % cases.size());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: " + DIM + ";");
        return l;
    }

    private Label severityBadge(Severity sev) {
        String bg, fg;
        switch (sev) {
            case ERROR   -> { bg = RED_BG;   fg = RED_DIM; }
            case WARNING -> { bg = AMBER_BG; fg = AMBER_DIM; }
            default      -> { bg = BLUE_BG;  fg = BLUE_EXTRA; }
        }
        Label l = new Label(sev.name());
        l.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; "
            + "-fx-background-radius: 10; -fx-padding: 2 10; "
            + "-fx-font-size: 10px; -fx-font-weight: bold;");
        return l;
    }

    private Button actionBtn(String text, String fg, String bg, String fgDim) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; "
            + "-fx-background-radius: 8; -fx-padding: 9 20; "
            + "-fx-font-size: 13px; -fx-font-weight: bold; -fx-cursor: hand; "
            + "-fx-border-color: " + fgDim + "; -fx-border-radius: 8;");
        btn.setOnMouseEntered(e -> btn.setOpacity(0.85));
        btn.setOnMouseExited (e -> btn.setOpacity(1.0));
        return btn;
    }
}
