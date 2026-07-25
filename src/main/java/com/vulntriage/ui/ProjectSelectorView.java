package com.vulntriage.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Startup project-selector screen shown before the main app initialises.
 *
 * Scans the "projects/" subfolder of the working directory for .db files and
 * lets the user pick one, open an arbitrary file, or create a new project.
 *
 * The selected path is delivered via the onProjectSelected callback so Main
 * can set the system property before AppContext/SQLiteConnection initialise.
 */
public class ProjectSelectorView {

    private static final String BG    = "#F1F5F9";
    private static final String CARD  = "#FFFFFF";
    private static final String TEXT  = "#111827";
    private static final String MUTED = "#6B7280";
    private static final String BLUE  = "#1D4ED8";
    private static final String MONO  = "Courier New";

    private static final Path PROJECTS_DIR = Paths.get("projects");
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private final Consumer<Path> onProjectSelected;
    private VBox projectList;
    private Stage stage;

    public ProjectSelectorView(Consumer<Path> onProjectSelected) {
        this.onProjectSelected = onProjectSelected;
    }

    public Scene build(Stage stage) {
        this.stage = stage;
        ensureProjectsDir();

        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: " + BG + ";");

        root.getChildren().addAll(buildHeader(), buildContent());

        return new Scene(root, 720, 540);
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private Node buildHeader() {
        VBox header = new VBox(6);
        header.setPadding(new Insets(36, 40, 28, 40));
        header.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: #E5E7EB; -fx-border-width: 0 0 1 0;");

        Label title = new Label("VulnTriage");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: " + BLUE + ";");

        Label sub = new Label("Select a project database to continue, or create a new one.");
        sub.setStyle("-fx-font-size: 13px; -fx-text-fill: " + MUTED + ";");

        header.getChildren().addAll(title, sub);
        return header;
    }

    // ── Content ────────────────────────────────────────────────────────────

    private Node buildContent() {
        VBox content = new VBox(16);
        content.setPadding(new Insets(28, 40, 28, 40));
        VBox.setVgrow(content, Priority.ALWAYS);

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_LEFT);

        Button newBtn = new Button("+ New Project");
        styleBtn(newBtn, true);
        newBtn.setOnAction(e -> showNewProjectDialog());

        Button openBtn = new Button("Open File...");
        styleBtn(openBtn, false);
        openBtn.setOnAction(e -> openFileChooser());

        actions.getChildren().addAll(newBtn, openBtn);

        Label listLabel = new Label("Projects");
        listLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; "
            + "-fx-text-fill: #9CA3AF; -fx-letter-spacing: 0.5px;");

        projectList = new VBox(8);

        ScrollPane scroll = new ScrollPane(projectList);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: " + BG + "; -fx-background-color: " + BG + "; "
            + "-fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        refreshProjectList();

        content.getChildren().addAll(actions, listLabel, scroll);
        return content;
    }

    // ── Project list ───────────────────────────────────────────────────────

    private void refreshProjectList() {
        projectList.getChildren().clear();
        List<Path> dbs = findDatabases();

        if (dbs.isEmpty()) {
            Label empty = new Label("No projects yet — create one above or open an existing .db file.");
            empty.setStyle("-fx-font-size: 13px; -fx-text-fill: " + MUTED + "; -fx-padding: 20 0;");
            projectList.getChildren().add(empty);
        } else {
            for (Path db : dbs) {
                projectList.getChildren().add(buildProjectCard(db));
            }
        }
    }

    private Node buildProjectCard(Path db) {
        HBox card = new HBox(14);
        card.setPadding(new Insets(14, 18, 14, 18));
        card.setAlignment(Pos.CENTER_LEFT);
        applyCardStyle(card, false);

        Label icon = new Label("◈");
        icon.setStyle("-fx-font-size: 20px; -fx-text-fill: " + BLUE + ";");

        VBox info = new VBox(3);
        HBox.setHgrow(info, Priority.ALWAYS);

        String name = db.getFileName().toString().replace(".db", "");
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        Label metaLabel = new Label(buildMeta(db));
        metaLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: '" + MONO + "';");

        info.getChildren().addAll(nameLabel, metaLabel);

        Button deleteBtn = new Button("Delete");
        deleteBtn.setMinWidth(Region.USE_PREF_SIZE);
        deleteBtn.setStyle("-fx-background-color: #FEF2F2; -fx-text-fill: #DC2626; "
            + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-background-radius: 7; "
            + "-fx-border-color: #FECACA; -fx-border-radius: 7; "
            + "-fx-padding: 8 12; -fx-cursor: hand;");
        deleteBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
            javafx.event.Event::consume);
        deleteBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Permanently delete project \"" + name + "\"?\n\n"
                + "This removes the .db file from disk. All findings, reviews, and "
                + "triage results stored in this project will be lost and cannot be recovered.",
                ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Delete Project");
            confirm.setHeaderText("Delete \"" + name + "\"?");
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    try {
                        Files.deleteIfExists(db);
                    } catch (Exception ex) {
                        new Alert(Alert.AlertType.ERROR,
                            "Could not delete file:\n" + ex.getMessage())
                            .showAndWait();
                    }
                    refreshProjectList();
                }
            });
        });

        card.getChildren().addAll(icon, info, deleteBtn);

        card.setOnMouseEntered(e -> applyCardStyle(card, true));
        card.setOnMouseExited(e  -> applyCardStyle(card, false));
        card.setOnMouseClicked(e -> onProjectSelected.accept(db.toAbsolutePath()));

        return card;
    }

    // ── Dialogs ────────────────────────────────────────────────────────────

    private void showNewProjectDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("New Project");
        dialog.setHeaderText("Enter a name for the new project:");

        TextField nameField = new TextField();
        nameField.setPromptText("e.g. webapp-audit");

        VBox content = new VBox(8);
        content.getChildren().addAll(new Label("Project name:"), nameField);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> bt == ButtonType.OK ? nameField.getText().trim() : null);

        javafx.application.Platform.runLater(nameField::requestFocus);

        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            String safe = name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            Path dbPath = PROJECTS_DIR.resolve(safe + ".db").toAbsolutePath();
            onProjectSelected.accept(dbPath);
        });
    }

    private void openFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open VulnTriage Database");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("SQLite Database (*.db)", "*.db"));

        File initialDir = PROJECTS_DIR.toAbsolutePath().toFile();
        if (initialDir.exists()) chooser.setInitialDirectory(initialDir);

        File file = chooser.showOpenDialog(stage);
        if (file != null) onProjectSelected.accept(file.toPath());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<Path> findDatabases() {
        try {
            return Files.list(PROJECTS_DIR)
                .filter(p -> p.toString().endsWith(".db"))
                .sorted((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(b)
                            .compareTo(Files.getLastModifiedTime(a));
                    } catch (Exception e) { return 0; }
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildMeta(Path db) {
        try {
            long bytes = Files.size(db);
            String size = bytes < 1024     ? bytes + " B"
                        : bytes < 1048576  ? (bytes / 1024) + " KB"
                        :                    (bytes / 1048576) + " MB";

            LocalDateTime modified = LocalDateTime.ofInstant(
                Files.getLastModifiedTime(db).toInstant(), ZoneId.systemDefault());

            return db.toAbsolutePath() + "  ·  " + size + "  ·  modified " + modified.format(FMT);
        } catch (Exception e) {
            return db.toAbsolutePath().toString();
        }
    }

    private void ensureProjectsDir() {
        try {
            if (!Files.exists(PROJECTS_DIR)) Files.createDirectories(PROJECTS_DIR);
        } catch (Exception ignored) {}
    }

    private void styleBtn(Button b, boolean primary) {
        if (primary) {
            b.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: white; "
                + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-background-radius: 7; "
                + "-fx-padding: 8 18; -fx-cursor: hand;");
        } else {
            b.setStyle("-fx-background-color: white; -fx-text-fill: " + TEXT + "; "
                + "-fx-font-size: 12px; -fx-background-radius: 7; "
                + "-fx-border-color: #D1D5DB; -fx-border-radius: 7; "
                + "-fx-padding: 8 18; -fx-cursor: hand;");
        }
    }

    private void applyCardStyle(HBox card, boolean hovered) {
        card.setStyle("-fx-background-color: " + (hovered ? "#F8FAFC" : CARD) + "; "
            + "-fx-background-radius: 9; "
            + "-fx-border-color: " + (hovered ? BLUE : "#E5E7EB") + "; "
            + "-fx-border-radius: 9; -fx-cursor: hand;");
    }
}
