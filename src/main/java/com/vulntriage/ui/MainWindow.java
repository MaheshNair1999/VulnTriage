package com.vulntriage.ui;

import com.vulntriage.app.Main;
import com.vulntriage.config.ThemeColors;
import com.vulntriage.ui.dashboard.DashboardView;
import com.vulntriage.ui.evaluation.EvaluationView;
import com.vulntriage.ui.workflow.WorkflowView;
import com.vulntriage.ui.review.ReviewView;
import com.vulntriage.ui.settings.SettingsView;
import com.vulntriage.ui.triage.TriageView;
import com.vulntriage.ui.triage.TargetedTriageView;
import com.vulntriage.ui.findings.FindingsView;
import com.vulntriage.ui.prompts.PromptsView;
import com.vulntriage.ui.repository.RepositoryView;
import com.vulntriage.app.AppContext;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static com.vulntriage.config.ThemeColors.*;

public class MainWindow {

    private final Stage             stage;
    private final Map<String, Node> viewCache   = new HashMap<>();
    private final List<NavButton>   navButtons  = new ArrayList<>();
    private final StackPane         contentArea = new StackPane();
    private BorderPane              borderPane;
    private String                  currentViewName = "Dashboard";

    public MainWindow(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        borderPane = new BorderPane();
        borderPane.setLeft  (buildSidebar());
        borderPane.setCenter(contentArea);

        navigate("Dashboard");

        Scene scene = new Scene(borderPane, 1280, 800);
        stage.setScene(scene);
        stage.setTitle("VulnTriage — " + currentProjectName());
        stage.setMinWidth (960);
        stage.setMinHeight(640);
        stage.setMaximized(false);
        stage.setMaximized(true);
        stage.show();

        // Rebuild all UI when dark mode toggles
        AppContext.getInstance().darkModeProperty().addListener((obs, wasOn, isOn) -> {
            viewCache.clear();
            triageViewNode = null;
            navButtons.clear();
            borderPane.setLeft(buildSidebar());
            navigate(currentViewName);
        });
    }

    private String currentProjectName() {
        String url = System.getProperty("vulntriage.db.url", "jdbc:sqlite:vulntriage.db");
        String path = url.replace("jdbc:sqlite:", "");
        String filename = Paths.get(path).getFileName().toString();
        return filename.endsWith(".db") ? filename.substring(0, filename.length() - 3) : filename;
    }

    private void switchProject() {
        stage.setMaximized(false);
        ProjectSelectorView selector = new ProjectSelectorView(path -> Main.openProject(stage, path));
        stage.setTitle("VulnTriage — Select Project");
        stage.setScene(selector.build(stage));
    }

    // ── Sidebar ────────────────────────────────────────────────────────────

    private VBox buildSidebar() {
        VBox sidebar = new VBox();
        sidebar.setPrefWidth(220);
        sidebar.setStyle("-fx-background-color: " + ThemeColors.SIDEBAR_BG + ";");

        VBox logo = new VBox(4);
        logo.setPadding(new Insets(24, 20, 20, 20));
        Label title = new Label("VulnTriage");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; "
            + "-fx-text-fill: white; -fx-font-family: 'Courier New';");
        Label subtitle = new Label("Security Analysis Platform");
        subtitle.setStyle("-fx-font-size: 10px; -fx-text-fill: " + ThemeColors.MUTED + ";");
        logo.getChildren().addAll(title, subtitle);

        HBox projectBadge = buildProjectBadge();

        Region div = new Region();
        div.setPrefHeight(1);
        div.setStyle("-fx-background-color: " + ThemeColors.SIDEBAR_HOVER + ";");

        Label navLabel = new Label("NAVIGATION");
        navLabel.setPadding(new Insets(16, 20, 6, 20));
        navLabel.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; "
            + "-fx-text-fill: " + ThemeColors.DIM + ";");

        VBox nav = new VBox(2);
        nav.setPadding(new Insets(0, 10, 0, 10));

        String[][] items = {
            {"⊞", "Dashboard"},
            {"⊕", "Repositories"},
            {"◈", "Findings"},
            {"✓", "Review"},
            {"⌨", "Prompts"},
            {"◉", "Triage"},
            {"⊚", "Triage Results"},
            {"⬡", "Workflows"},
            {"≡", "Evaluate"},
            {"⚙", "Settings"}
        };

        for (String[] item : items) {
            NavButton btn = new NavButton(item[0], item[1], () -> navigate(item[1]));
            navButtons.add(btn);
            nav.getChildren().add(btn);
        }

        // Restore active state on rebuild
        navButtons.forEach(btn -> {
            if (btn.getLabel().equals(currentViewName)) btn.setActive();
        });

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox triageIndicator   = buildTriageIndicator();
        VBox workflowIndicator = buildWorkflowIndicator();
        VBox scanIndicator     = buildScanIndicator();

        Label version = new Label("v1.0.0  ·  Java 17  ·  SQLite");
        version.setPadding(new Insets(4, 20, 16, 20));
        version.setStyle("-fx-font-size: 9px; -fx-text-fill: " + ThemeColors.DIM + ";");

        sidebar.getChildren().addAll(logo, projectBadge, div, navLabel, nav, spacer, scanIndicator, workflowIndicator, triageIndicator, version);
        return sidebar;
    }

    // ── Project badge ──────────────────────────────────────────────────────

    private HBox buildProjectBadge() {
        HBox badge = new HBox(8);
        badge.setPadding(new Insets(10, 14, 10, 14));
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setStyle("-fx-background-color: " + ThemeColors.SIDEBAR_HOVER + "; -fx-cursor: hand;");

        Label icon = new Label("◈");
        icon.setStyle("-fx-font-size: 12px; -fx-text-fill: #60A5FA;");

        Label name = new Label(currentProjectName());
        name.setStyle("-fx-font-size: 12px; -fx-text-fill: #D1D5DB; "
            + "-fx-font-family: 'Courier New';");
        name.setMaxWidth(130);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label arrow = new Label("⇄");
        arrow.setStyle("-fx-font-size: 11px; -fx-text-fill: #4B5563;");

        badge.getChildren().addAll(icon, name, arrow);

        String hoverBg  = ThemeColors.DIM;
        String normalBg = ThemeColors.SIDEBAR_HOVER;
        badge.setOnMouseEntered(e -> badge.setStyle("-fx-background-color: " + hoverBg + "; -fx-cursor: hand;"));
        badge.setOnMouseExited (e -> badge.setStyle("-fx-background-color: " + normalBg + "; -fx-cursor: hand;"));
        badge.setOnMouseClicked(e -> switchProject());

        return badge;
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private static final java.util.Set<String> NO_CACHE =
        java.util.Set.of("Dashboard", "Findings", "Review", "Evaluate");

    private Node triageViewNode;

    private void navigate(String name) {
        if (!contentArea.getChildren().isEmpty()) {
            Node current = contentArea.getChildren().get(0);
            java.util.function.BooleanSupplier guard =
                (java.util.function.BooleanSupplier) current.getProperties().get("navigationGuard");
            if (guard != null && !guard.getAsBoolean()) return;
        }

        currentViewName = name;

        navButtons.forEach(btn -> {
            if (btn.getLabel().equals(name)) btn.setActive();
            else                             btn.setInactive();
        });

        Node view;
        try {
            if (name.equals("Triage")) {
                if (triageViewNode == null) triageViewNode = createView("Triage");
                view = triageViewNode;
            } else if (NO_CACHE.contains(name)) {
                view = createView(name);
            } else {
                view = viewCache.computeIfAbsent(name, this::createView);
            }
        } catch (Throwable ex) {
            view = buildErrorCard(name, ex);
            viewCache.remove(name);
            if (name.equals("Triage")) triageViewNode = null;
        }
        contentArea.getChildren().setAll(view);
    }

    private Node createView(String name) {
        return switch (name) {
            case "Dashboard"      -> new DashboardView().build();
            case "Repositories"   -> new RepositoryView().build();
            case "Findings"       -> new FindingsView().build();
            case "Review"         -> new ReviewView().build();
            case "Triage"         -> new TargetedTriageView().build();
            case "Triage Results" -> new TriageView().build();
            case "Workflows"      -> new WorkflowView().build();
            case "Evaluate"       -> new EvaluationView().build();
            case "Prompts"        -> new PromptsView().build();
            case "Settings"       -> new SettingsView().build();
            default               -> buildPlaceholder(name);
        };
    }

    // ── Running indicators ─────────────────────────────────────────────────

    private VBox buildTriageIndicator() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(10, 16, 10, 16));
        box.setStyle("-fx-background-color: " + ThemeColors.SIDEBAR_HOVER + "; -fx-background-radius: 8;");
        box.setVisible(false);
        box.setManaged(false);

        Label spinner = new Label("◉  Triage");
        spinner.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #60A5FA;");

        Label subLabel = new Label("Running in background…");
        subLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #9CA3AF;");

        box.getChildren().addAll(spinner, subLabel);

        String[] frames = {"◉", "◎", "○", "◎"};
        final int[] idx = {0};
        Timeline anim = new Timeline(new KeyFrame(Duration.millis(400), e -> {
            idx[0] = (idx[0] + 1) % frames.length;
            spinner.setText(frames[idx[0]] + "  Triage");
        }));
        anim.setCycleCount(Animation.INDEFINITE);

        AppContext.getInstance().triageRunningProperty().addListener((obs, wasRunning, isRunning) -> {
            box.setVisible(isRunning);
            box.setManaged(isRunning);
            if (isRunning) anim.play();
            else           anim.stop();
        });

        return box;
    }

    private VBox buildWorkflowIndicator() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(10, 16, 10, 16));
        box.setStyle("-fx-background-color: " + ThemeColors.SIDEBAR_HOVER + "; -fx-background-radius: 8;");
        box.setVisible(false);
        box.setManaged(false);

        Label spinner = new Label("⬡  Workflow");
        spinner.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #34D399;");

        Label subLabel = new Label("Running in background…");
        subLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #9CA3AF;");

        box.getChildren().addAll(spinner, subLabel);

        String[] frames = {"⬡", "⬢", "⬡", "⬢"};
        final int[] idx = {0};
        Timeline anim = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            idx[0] = (idx[0] + 1) % frames.length;
            spinner.setText(frames[idx[0]] + "  Workflow");
        }));
        anim.setCycleCount(Animation.INDEFINITE);

        AppContext.getInstance().workflowRunningProperty().addListener((obs, wasRunning, isRunning) -> {
            box.setVisible(isRunning);
            box.setManaged(isRunning);
            if (isRunning) anim.play();
            else           anim.stop();
        });

        return box;
    }

    private VBox buildScanIndicator() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(10, 16, 10, 16));
        box.setStyle("-fx-background-color: " + ThemeColors.SIDEBAR_HOVER + "; -fx-background-radius: 8;");
        box.setVisible(false);
        box.setManaged(false);

        Label spinner = new Label("⬡  Scanning");
        spinner.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #F59E0B;");

        Label subLabel = new Label("Running in background…");
        subLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #9CA3AF;");

        box.getChildren().addAll(spinner, subLabel);

        String[] frames = {"◐", "◓", "◑", "◒"};
        final int[] idx = {0};
        Timeline anim = new Timeline(new KeyFrame(Duration.millis(300), e -> {
            idx[0] = (idx[0] + 1) % frames.length;
            spinner.setText(frames[idx[0]] + "  Scanning");
        }));
        anim.setCycleCount(Animation.INDEFINITE);

        AppContext.getInstance().scanRunningProperty().addListener((obs, wasRunning, isRunning) -> {
            box.setVisible(isRunning);
            box.setManaged(isRunning);
            if (isRunning) anim.play();
            else           anim.stop();
        });

        return box;
    }

    private Node buildPlaceholder(String name) {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: " + ThemeColors.BG + ";");
        Label label = new Label(name);
        label.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #9CA3AF;");
        Label sub = new Label("Coming in Week 6");
        sub.setStyle("-fx-font-size: 14px; -fx-text-fill: #D1D5DB;");
        box.getChildren().addAll(label, sub);
        return box;
    }

    private Node buildErrorCard(String viewName, Throwable ex) {
        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        box.setStyle("-fx-background-color: " + ThemeColors.BG + ";");

        Label title = new Label("Failed to open \"" + viewName + "\"");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #DC2626;");

        Label msg = new Label(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        msg.setStyle("-fx-font-size: 12px; -fx-text-fill: " + ThemeColors.MUTED + ";");
        msg.setWrapText(true);
        msg.setMaxWidth(520);

        Label hint = new Label("Click the nav item again to retry.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #9CA3AF; -fx-font-style: italic;");

        box.getChildren().addAll(title, msg, hint);
        return box;
    }
}