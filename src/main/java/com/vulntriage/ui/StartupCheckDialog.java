package com.vulntriage.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.vulntriage.config.ThemeColors.*;

/**
 * Startup dialog that checks all required external tools and shows
 * green/red status with install instructions for anything missing.
 * Runs checks in the background so the UI stays responsive.
 */
public class StartupCheckDialog {

    private record Tool(
        String name,
        String description,
        String[] command,
        String installHint
    ) {}

    private static final List<Tool> TOOLS = List.of(
        new Tool(
            "Java 17+",
            "Runtime (already running)",
            null,
            "Download from https://adoptium.net"
        ),
        new Tool(
            "WSL",
            "Windows Subsystem for Linux (needed for Semgrep)",
            new String[]{"wsl", "--version"},
            "Run in PowerShell (admin): wsl --install"
        ),
        new Tool(
            "Semgrep",
            "SAST scanner (runs inside WSL)",
            new String[]{"wsl", "semgrep", "--version"},
            "In WSL terminal: pip install semgrep"
        ),
        new Tool(
            "Trivy",
            "Dependency vulnerability scanner",
            new String[]{"trivy", "--version"},
            "Download from https://trivy.dev  →  add to PATH"
        ),
        new Tool(
            "Gitleaks",
            "Secret & credential detection",
            new String[]{"gitleaks", "version"},
            "Download from github.com/gitleaks/gitleaks/releases  →  add to PATH"
        ),
        new Tool(
            "CodeQL",
            "Deep SAST (requires CLI v2.27.1+)",
            new String[]{"codeql", "version"},
            "Download from github.com/github/codeql-action/releases  →  add to PATH"
        )
    );

    public static void show(Window owner) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Tool Check");
        dialog.setHeaderText("Checking required tools…");
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.getDialogPane().setPrefWidth(620);
        UIUtils.applyTheme(dialog);

        VBox rows = new VBox(6);
        rows.setPadding(new Insets(12, 16, 8, 16));

        // Header row
        HBox header = new HBox();
        Label hTool   = bold("Tool");         hTool.setPrefWidth(120);
        Label hDesc   = bold("Description");  hDesc.setPrefWidth(230);
        Label hStatus = bold("Status");       hStatus.setPrefWidth(80);
        header.getChildren().addAll(hTool, hDesc, hStatus);
        rows.getChildren().add(header);
        rows.getChildren().add(new Separator());

        // One row per tool
        Label[] statusLabels = new Label[TOOLS.size()];
        Label[] hintLabels   = new Label[TOOLS.size()];
        VBox[]  hintBoxes    = new VBox[TOOLS.size()];

        for (int i = 0; i < TOOLS.size(); i++) {
            Tool t = TOOLS.get(i);

            Label nameL = new Label(t.name());
            nameL.setPrefWidth(120);
            nameL.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

            Label descL = new Label(t.description());
            descL.setPrefWidth(230);
            descL.setWrapText(true);
            descL.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");

            Label statusL = new Label("Checking…");
            statusL.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
            statusLabels[i] = statusL;

            HBox row = new HBox();
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(nameL, descL, statusL);

            Label hintL = new Label("  → " + t.installHint());
            hintL.setStyle("-fx-font-size: 11px; -fx-text-fill: #F59E0B; -fx-font-style: italic;");
            hintL.setWrapText(true);
            hintL.setPadding(new Insets(0, 0, 0, 120));
            hintLabels[i]   = hintL;

            VBox hintBox = new VBox(row, hintL);
            hintL.setVisible(false);
            hintL.setManaged(false);
            hintBoxes[i] = hintBox;
            rows.getChildren().add(hintBox);
        }

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(340);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        dialog.getDialogPane().setContent(scroll);

        // Run checks in background
        Thread checker = new Thread(() -> {
            int missing = 0;
            for (int i = 0; i < TOOLS.size(); i++) {
                Tool t = TOOLS.get(i);
                final int idx = i;
                boolean ok;

                if (t.command() == null) {
                    // Java — already running, just check version
                    String version = System.getProperty("java.version", "");
                    ok = !version.isBlank();
                } else {
                    ok = probe(t.command());
                }

                final boolean found = ok;
                if (!found) missing++;
                Platform.runLater(() -> {
                    if (found) {
                        statusLabels[idx].setText("✓ Found");
                        statusLabels[idx].setStyle("-fx-font-size: 12px; -fx-text-fill: #22C55E; -fx-font-weight: bold;");
                        hintLabels[idx].setVisible(false);
                        hintLabels[idx].setManaged(false);
                    } else {
                        statusLabels[idx].setText("✗ Missing");
                        statusLabels[idx].setStyle("-fx-font-size: 12px; -fx-text-fill: #EF4444; -fx-font-weight: bold;");
                        hintLabels[idx].setVisible(true);
                        hintLabels[idx].setManaged(true);
                    }
                });
            }

            final int totalMissing = missing;
            Platform.runLater(() -> {
                if (totalMissing == 0) {
                    dialog.setHeaderText("All tools found — ready to scan.");
                } else {
                    dialog.setHeaderText(totalMissing + " tool(s) missing. Install them to enable those scanners.");
                }
            });
        }, "tool-check-thread");
        checker.setDaemon(true);
        checker.start();

        dialog.showAndWait();
    }

    private static boolean probe(String[] command) {
        try {
            Process p = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor(8, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
        return l;
    }
}
