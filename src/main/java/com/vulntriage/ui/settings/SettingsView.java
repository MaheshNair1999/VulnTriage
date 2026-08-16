package com.vulntriage.ui.settings;

import com.vulntriage.app.AppContext;
import com.vulntriage.triage.ollama.OllamaTriageStrategy;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.application.Platform;
import static com.vulntriage.config.ThemeColors.*;

/**
 * Settings screen — configure Ollama connection and application preferences.
 */
public class SettingsView {


    private final AppContext ctx = AppContext.getInstance();

    private TextField urlField;
    private TextField modelField;
    private TextField delayField;
    private Label     connectionStatus;

    public Node build() {
        ScrollPane scroll = new ScrollPane(buildContent());
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + BG
            + "; -fx-background: " + BG + "; -fx-border-color: transparent;");
        return scroll;
    }

    private VBox buildContent() {
        VBox root = new VBox(24);
        root.setPadding(new Insets(28, 32, 28, 32));
        root.setStyle("-fx-background-color: " + BG + ";");

        Label title = new Label("Settings");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        root.getChildren().addAll(title, buildOllamaCard(), buildAboutCard());
        return root;
    }

    // ── Ollama card ────────────────────────────────────────────────────────

    private VBox buildOllamaCard() {
        VBox card = card("Ollama — LLM Backend");

        Label desc = new Label(
            "VulnTriage uses a locally running Ollama instance for LLM-assisted triage. "
            + "Install Ollama from ollama.ai, pull a model (e.g. ollama pull qwen3:14b), "
            + "then configure the connection below.");
        desc.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        desc.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(16); grid.setVgap(12);
        grid.setPadding(new Insets(8, 0, 8, 0));

        urlField   = field(ctx.getOllamaUrl());
        modelField = field(ctx.getOllamaModel());
        delayField = field(String.valueOf(com.vulntriage.config.AppConfig.getInstance().getTriageDelayMs()));

        grid.addRow(0, label("Ollama URL"),        urlField);
        grid.addRow(1, label("Model Name"),        modelField);
        grid.addRow(2, label("Delay Between\nRequests (ms)"), delayField);
        ColumnConstraints cc1 = new ColumnConstraints(130);
        ColumnConstraints cc2 = new ColumnConstraints();
        cc2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(cc1, cc2);

        connectionStatus = new Label("Not checked — click Test Connection to verify.");
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        connectionStatus.setWrapText(true);

        HBox btnRow = new HBox(10);
        Button testBtn  = secondaryBtn("Test Connection");
        Button saveBtn  = primaryBtn("Save & Enable Ollama");
        Button mockBtn  = new Button("Use Mock (testing)");
        mockBtn.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + MUTED + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 14; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6;");

        testBtn.setOnAction(e -> testConnection());
        saveBtn.setOnAction(e -> saveOllamaSettings());
        mockBtn.setOnAction(e -> {
            ctx.enableMock();
            connectionStatus.setText("Mock strategy enabled — no Ollama needed. Suitable for testing only.");
            connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        });

        btnRow.getChildren().addAll(testBtn, saveBtn, mockBtn);

        card.getChildren().addAll(desc, grid, connectionStatus, btnRow);
        return card;
    }

    // ── About card ─────────────────────────────────────────────────────────

    private VBox buildAboutCard() {
        VBox card = card("About VulnTriage");

        String[][] rows = {
            {"Application",  "VulnTriage v1.0.0"},
            {"Purpose",      "AI-Assisted Vulnerability Triage and Prioritisation"},
            {"Author",       "Mahesh Nair"},
            {"Supervisors",  "Prof. Francesco La Rosa  ·  Prof. Pierluigi Dell'Acqua"},
            {"Stack",        "Java 17  ·  JavaFX 21  ·  SQLite  ·  Ollama (Qwen3:14b)"},
            {"Database",     "vulntriage.db (SQLite, local)"},
        };

        GridPane grid = new GridPane();
        grid.setHgap(16); grid.setVgap(10);

        for (int i = 0; i < rows.length; i++) {
            Label key = new Label(rows[i][0]);
            key.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + MUTED + ";");
            key.setPrefWidth(140);
            Label val = new Label(rows[i][1]);
            val.setStyle("-fx-font-size: 12px; -fx-text-fill: " + TEXT + ";");
            grid.addRow(i, key, val);
        }

        card.getChildren().add(grid);
        return card;
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private void testConnection() {
        String url   = urlField.getText().trim();
        String model = modelField.getText().trim();

        connectionStatus.setText("Testing connection to " + url + "…");
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return new OllamaTriageStrategy(url, model).isAvailable();
            }
        };

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            if (task.getValue()) {
                connectionStatus.setText("✓ Connected — " + model
                    + " is available at " + url);
                connectionStatus.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; "
                    + "-fx-text-fill: " + GREEN + ";");
            } else {
                connectionStatus.setText("✗ Cannot reach Ollama at " + url
                    + "\nCheck that Ollama is running: ollama serve"
                    + "\nAnd that the model is pulled: ollama pull " + model);
                connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
            }
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            connectionStatus.setText("✗ Error: " + task.getException().getMessage());
            connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
        }));

        new Thread(task, "settings-check").start();
    }

    private void saveOllamaSettings() {
        String url   = urlField.getText().trim();
        String model = modelField.getText().trim();

        if (url.isBlank() || model.isBlank()) {
            connectionStatus.setText("URL and model name are required.");
            return;
        }

        // Parse and save delay
        try {
            int delay = Integer.parseInt(delayField.getText().trim());
            if (delay < 0) throw new NumberFormatException();
            com.vulntriage.config.AppConfig.getInstance().saveTriageDelay(delay);
        } catch (NumberFormatException e) {
            connectionStatus.setText("Delay must be a non-negative integer (milliseconds).");
            return;
        }

        ctx.enableOllama(url, model);
        connectionStatus.setText("✓ Ollama enabled — " + model + " at " + url
            + "\nThe Triage screen will now use this model.");
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + GREEN + ";");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private VBox card(String title) {
        VBox card = new VBox(14);
        card.setPadding(new Insets(22, 24, 22, 24));
        card.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 10; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 8, 0, 0, 2);");

        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");
        card.getChildren().add(heading);

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: " + BORDER + ";");
        card.getChildren().add(sep);

        return card;
    }

    private TextField field(String value) {
        TextField f = new TextField(value);
        f.setStyle("-fx-font-size: 12px; -fx-border-color: " + BORDER + "; "
            + "-fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 6 10;");
        return f;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + DIM + ";");
        l.setPrefWidth(130);
        return l;
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
}