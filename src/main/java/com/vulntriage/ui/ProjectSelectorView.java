package com.vulntriage.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import com.vulntriage.app.AppContext;
import com.vulntriage.config.ThemeColors;
import java.io.File;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import static com.vulntriage.config.ThemeColors.*;

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

    private static final String MONO  = "Courier New";

    private static final Path PROJECTS_DIR = resolveProjectsDir();

    private static Path resolveProjectsDir() {
        try {
            // Jar lives at <install>/app/vulntriage-1.0.0.jar — go up two levels to get install dir
            Path jar = Path.of(ProjectSelectorView.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getParent().getParent().resolve("projects");
        } catch (Exception e) {
            return Paths.get("projects");
        }
    }
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

        Scene scene = new Scene(root, 720, 540);
        try {
            boolean dark = AppContext.getInstance().isDarkMode();
            if (dark) {
                java.net.URL css = getClass().getResource("/dark-mode.css");
                if (css != null) scene.getStylesheets().add(css.toExternalForm());
                ThemeColors.apply(true);
            }
        } catch (Exception ignored) {}
        return scene;
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private Node buildHeader() {
        VBox header = new VBox(6);
        header.setPadding(new Insets(36, 40, 28, 40));
        header.setStyle("-fx-background-color: " + CARD + "; "
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 1 0;");

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
        projectList.setFillWidth(true);

        StackPane listWrap = new StackPane(projectList);
        listWrap.setStyle("-fx-background-color: " + BG + ";");
        StackPane.setAlignment(projectList, Pos.TOP_LEFT);
        VBox.setVgrow(listWrap, Priority.ALWAYS);

        refreshProjectList();

        content.getChildren().addAll(actions, listLabel, listWrap);
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
        String name = db.getFileName().toString().replace(".db", "");

        Label icon = new Label("◈");
        icon.setStyle("-fx-font-size: 20px; -fx-text-fill: " + BLUE + ";");

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + TEXT + ";");

        Label metaLabel = new Label(buildMeta(db));
        metaLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + MUTED
            + "; -fx-font-family: '" + MONO + "';");

        VBox info = new VBox(3, nameLabel, metaLabel);

        HBox graphic = new HBox(14, icon, info);
        graphic.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);
        graphic.setMaxWidth(Double.MAX_VALUE);

        // Main clickable button — most reliable event handling in JavaFX
        Button openBtn = new Button();
        openBtn.setGraphic(graphic);
        openBtn.setMaxWidth(Double.MAX_VALUE);
        openBtn.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 9; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 9; "
            + "-fx-padding: 14 18; -fx-cursor: hand; -fx-alignment: CENTER-LEFT;");
        openBtn.setOnAction(e -> onProjectSelected.accept(db.toAbsolutePath()));
        openBtn.setOnMouseEntered(e -> openBtn.setStyle(
            "-fx-background-color: " + SURFACE2 + "; -fx-background-radius: 9; "
            + "-fx-border-color: " + BLUE + "; -fx-border-radius: 9; "
            + "-fx-padding: 14 18; -fx-cursor: hand; -fx-alignment: CENTER-LEFT;"));
        openBtn.setOnMouseExited(e -> openBtn.setStyle(
            "-fx-background-color: " + CARD + "; -fx-background-radius: 9; "
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 9; "
            + "-fx-padding: 14 18; -fx-cursor: hand; -fx-alignment: CENTER-LEFT;"));
        HBox.setHgrow(openBtn, Priority.ALWAYS);

        Button deleteBtn = new Button("Delete");
        deleteBtn.setMinWidth(Region.USE_PREF_SIZE);
        deleteBtn.setStyle("-fx-background-color: " + RED_LIGHT_BG + "; -fx-text-fill: #DC2626; "
            + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-background-radius: 7; "
            + "-fx-border-color: " + RED_BORDER + "; -fx-border-radius: 7; "
            + "-fx-padding: 8 12; -fx-cursor: hand;");
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
                    // Close the SQLite connection if this is the currently open project,
                    // otherwise Windows keeps a file lock and deletion fails.
                    String activeUrl = System.getProperty("vulntriage.db.url", "");
                    String activeFile = activeUrl.replace("jdbc:sqlite:", "");
                    try {
                        if (java.nio.file.Paths.get(activeFile).toAbsolutePath()
                                .equals(db.toAbsolutePath())) {
                            com.vulntriage.repository.sqlite.SQLiteConnection.reset();
                        }
                        Files.deleteIfExists(db);
                        // Clean up SQLite WAL/SHM sidecar files if present
                        Files.deleteIfExists(db.resolveSibling(db.getFileName() + "-wal"));
                        Files.deleteIfExists(db.resolveSibling(db.getFileName() + "-shm"));
                    } catch (Exception ex) {
                        new Alert(Alert.AlertType.ERROR,
                            "Could not delete:\n" + db + "\n\n" + ex.getMessage()).showAndWait();
                    }
                    refreshProjectList();
                }
            });
        });

        HBox row = new HBox(8, openBtn, deleteBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
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
            b.setStyle("-fx-background-color: " + CARD + "; -fx-text-fill: " + TEXT + "; "
                + "-fx-font-size: 12px; -fx-background-radius: 7; "
                + "-fx-border-color: " + BORDER + "; -fx-border-radius: 7; "
                + "-fx-padding: 8 18; -fx-cursor: hand;");
        }
    }

}