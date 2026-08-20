package com.vulntriage.ui.settings;

import com.vulntriage.app.AppContext;
import com.vulntriage.config.AppConfig;
import com.vulntriage.triage.cloud.CloudTriageStrategy;
import com.vulntriage.triage.cloud.LlmProvider;
import com.vulntriage.triage.ollama.OllamaTriageStrategy;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import static com.vulntriage.config.ThemeColors.*;

public class SettingsView {

    private final AppContext ctx = AppContext.getInstance();
    private final AppConfig  cfg = AppConfig.getInstance();

    // Ollama fields
    private TextField     urlField;
    private TextField     modelField;
    private TextField     delayField;

    // Cloud fields
    private PasswordField apiKeyField;
    private TextField     cloudModelField;

    // Shared
    private Label                 connectionStatus;
    private ComboBox<LlmProvider> providerBox;
    private VBox                  ollamaFields;
    private VBox                  cloudFields;
    private Button                saveBtn;

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

        root.getChildren().addAll(title, buildLlmCard(), buildAboutCard());
        return root;
    }

    // ── LLM provider card ──────────────────────────────────────────────────

    private VBox buildLlmCard() {
        VBox card = card("LLM Backend");

        Label desc = new Label(
            "Choose which AI provider to use for vulnerability triage. "
            + "Ollama runs locally with no API key. Cloud providers require an API key from the provider's dashboard.");
        desc.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");
        desc.setWrapText(true);

        // ── Provider dropdown ──────────────────────────────────────────────
        ColumnConstraints lc = new ColumnConstraints(130);
        ColumnConstraints fc = new ColumnConstraints();
        fc.setHgrow(Priority.ALWAYS);

        providerBox = new ComboBox<>();
        providerBox.getItems().addAll(LlmProvider.values());
        providerBox.setValue(cfg.getActiveProvider());
        providerBox.setMaxWidth(Double.MAX_VALUE);
        providerBox.setStyle("-fx-font-size: 12px;");

        GridPane providerRow = new GridPane();
        providerRow.setHgap(16);
        providerRow.getColumnConstraints().addAll(lc, fc);
        providerRow.addRow(0, label("Provider"), providerBox);

        // ── Ollama-specific fields ─────────────────────────────────────────
        urlField   = field(ctx.getOllamaUrl());
        modelField = field(ctx.getOllamaModel());
        delayField = field(String.valueOf(cfg.getTriageDelayMs()));

        GridPane ollamaGrid = new GridPane();
        ollamaGrid.setHgap(16); ollamaGrid.setVgap(12);
        ollamaGrid.getColumnConstraints().addAll(new ColumnConstraints(130), fc);
        ollamaGrid.addRow(0, label("Ollama URL"),  urlField);
        ollamaGrid.addRow(1, label("Model Name"),  modelField);
        ollamaGrid.addRow(2, label("Delay (ms)"),  delayField);

        Label ollamaHint = new Label("Install Ollama from ollama.ai — then run: ollama pull qwen3:14b");
        ollamaHint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");

        ollamaFields = new VBox(10, ollamaGrid, ollamaHint);

        // ── Cloud-specific fields ──────────────────────────────────────────
        apiKeyField = new PasswordField();
        apiKeyField.setStyle("-fx-font-size: 12px; -fx-border-color: " + BORDER
            + "; -fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 6 10;");
        String savedKey = cfg.getLlmApiKey();
        if (savedKey != null) apiKeyField.setText(savedKey);

        String savedModel = cfg.getLlmModel();
        cloudModelField = field(savedModel == null || savedModel.isBlank()
            ? cfg.getActiveProvider().getDefaultModel() : savedModel);

        GridPane cloudGrid = new GridPane();
        cloudGrid.setHgap(16); cloudGrid.setVgap(12);
        cloudGrid.getColumnConstraints().addAll(new ColumnConstraints(130), fc);
        cloudGrid.addRow(0, label("API Key"),    apiKeyField);
        cloudGrid.addRow(1, label("Model Name"), cloudModelField);

        Label cloudHint = new Label(
            "Get your API key from the provider's dashboard. It is stored locally in the app database.");
        cloudHint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        cloudHint.setWrapText(true);

        cloudFields = new VBox(10, cloudGrid, cloudHint);

        // ── Toggle visibility when provider changes ─────────────────────────
        providerBox.valueProperty().addListener((obs, old, selected) -> {
            boolean isOllama = selected == LlmProvider.OLLAMA;
            ollamaFields.setVisible(isOllama);
            ollamaFields.setManaged(isOllama);
            cloudFields.setVisible(!isOllama);
            cloudFields.setManaged(!isOllama);
            if (!isOllama) {
                String cur = cloudModelField.getText();
                if (cur.isBlank() || (old != null && old.getDefaultModel().equals(cur))) {
                    cloudModelField.setText(selected.getDefaultModel());
                }
            }
            updateSaveLabel(selected);
        });

        boolean initOllama = cfg.getActiveProvider() == LlmProvider.OLLAMA;
        ollamaFields.setVisible(initOllama);  ollamaFields.setManaged(initOllama);
        cloudFields.setVisible(!initOllama);  cloudFields.setManaged(!initOllama);

        // ── Status + buttons ───────────────────────────────────────────────
        connectionStatus = new Label("Not checked — click Test Connection to verify.");
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        connectionStatus.setWrapText(true);

        Button testBtn = secondaryBtn("Test Connection");
        saveBtn        = primaryBtn("Save & Enable Ollama");
        updateSaveLabel(cfg.getActiveProvider());

        Button mockBtn = new Button("Use Mock (testing)");
        mockBtn.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + MUTED + "; "
            + "-fx-background-radius: 6; -fx-font-size: 12px; -fx-padding: 7 14; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6;");

        testBtn.setOnAction(e -> testConnection());
        saveBtn.setOnAction(e -> saveSettings());
        mockBtn.setOnAction(e -> {
            ctx.enableMock();
            connectionStatus.setText("Mock strategy enabled — no LLM needed. Suitable for testing only.");
            connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
        });

        HBox btnRow = new HBox(10, testBtn, saveBtn, mockBtn);

        card.getChildren().addAll(desc, providerRow, ollamaFields, cloudFields, connectionStatus, btnRow);
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
            {"Stack",        "Java 17  ·  JavaFX 21  ·  SQLite  ·  Ollama / Cloud LLM"},
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
        LlmProvider provider = providerBox.getValue();

        if (provider == LlmProvider.OLLAMA) {
            String url   = urlField.getText().trim();
            String model = modelField.getText().trim();
            connectionStatus.setText("Testing connection to " + url + "…");
            connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
            Task<Boolean> task = new Task<>() {
                @Override protected Boolean call() {
                    return new OllamaTriageStrategy(url, model).isAvailable();
                }
            };
            task.setOnSucceeded(e -> Platform.runLater(() -> {
                if (task.getValue()) {
                    ok("Connected — " + model + " is available at " + url);
                } else {
                    err("Cannot reach Ollama at " + url
                        + "\nCheck that Ollama is running: ollama serve"
                        + "\nModel must be pulled: ollama pull " + model);
                }
            }));
            task.setOnFailed(e -> Platform.runLater(() ->
                err("Error: " + task.getException().getMessage())));
            new Thread(task, "settings-check").start();
        } else {
            String apiKey = apiKeyField.getText().trim();
            String model  = cloudModelField.getText().trim();
            if (apiKey.isBlank()) { err("API key is required."); return; }
            connectionStatus.setText("Testing connection to " + provider.getDisplayName() + "…");
            connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED + ";");
            Task<Boolean> task = new Task<>() {
                @Override protected Boolean call() {
                    return new CloudTriageStrategy(provider, apiKey, model).isAvailable();
                }
            };
            task.setOnSucceeded(e -> Platform.runLater(() -> {
                if (task.getValue()) {
                    ok("Connected — " + provider.getDisplayName() + " API key is valid.");
                } else {
                    err("Could not reach " + provider.getDisplayName()
                        + ". Check your API key and internet connection.");
                }
            }));
            task.setOnFailed(e -> Platform.runLater(() ->
                err("Error: " + task.getException().getMessage())));
            new Thread(task, "settings-check").start();
        }
    }

    private void saveSettings() {
        LlmProvider provider = providerBox.getValue();

        if (provider == LlmProvider.OLLAMA) {
            String url   = urlField.getText().trim();
            String model = modelField.getText().trim();
            if (url.isBlank() || model.isBlank()) { err("URL and model name are required."); return; }
            try {
                int delay = Integer.parseInt(delayField.getText().trim());
                if (delay < 0) throw new NumberFormatException();
                cfg.saveTriageDelay(delay);
            } catch (NumberFormatException e) {
                err("Delay must be a non-negative integer (milliseconds)."); return;
            }
            ctx.enableOllama(url, model);
            ok("Ollama enabled — " + model + " at " + url);
        } else {
            String apiKey = apiKeyField.getText().trim();
            String model  = cloudModelField.getText().trim();
            if (apiKey.isBlank()) { err("API key is required."); return; }
            if (model.isBlank())  { err("Model name is required."); return; }
            ctx.enableCloudProvider(provider, apiKey, model);
            ok(provider.getDisplayName() + " enabled — model: " + model
                + "\nThe Triage screen will now use this provider.");
        }
    }

    private void updateSaveLabel(LlmProvider p) {
        if (saveBtn == null) return;
        saveBtn.setText(p == LlmProvider.OLLAMA
            ? "Save & Enable Ollama"
            : "Save & Enable " + p.getDisplayName());
    }

    private void ok(String msg) {
        connectionStatus.setText("✓ " + msg);
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + GREEN + ";");
    }

    private void err(String msg) {
        connectionStatus.setText("✗ " + msg);
        connectionStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: " + RED + ";");
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
        f.setStyle("-fx-font-size: 12px; -fx-border-color: " + BORDER
            + "; -fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 6 10;");
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
