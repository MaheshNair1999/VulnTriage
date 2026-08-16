package com.vulntriage.app;

import com.vulntriage.config.GlobalPrefs;
import com.vulntriage.repository.sqlite.SQLiteConnection;
import com.vulntriage.ui.MainWindow;
import com.vulntriage.ui.ProjectSelectorView;
import com.vulntriage.ui.WindowsDarkMode;
import javafx.application.Application;
import javafx.stage.Stage;

import java.nio.file.Path;

/**
 * JavaFX entry point.
 *
 * Shows the project-selector screen first so the user can pick (or create) a
 * database before AppContext / SQLiteConnection initialise. The selected path
 * is written to the system property "vulntriage.db.url" which SQLiteConnection
 * reads once at class-load time.
 *
 * Run via Maven: mvn javafx:run
 * Or build a fat JAR: mvn package, then java -jar target/vulntriage-1.0.0.jar
 */
public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("VulnTriage — Select Project");

        ProjectSelectorView selector = new ProjectSelectorView(
            path -> openProject(primaryStage, path));

        javafx.scene.Scene scene = selector.build(primaryStage);

        java.nio.file.Path log = java.nio.file.Path.of(
            System.getProperty("user.home"), "vt_input_log.txt");

        scene.addEventFilter(javafx.scene.input.MouseEvent.ANY, e -> {
            try { java.nio.file.Files.writeString(log,
                "MOUSE " + e.getEventType() + " at " + (int)e.getSceneX() + "," + (int)e.getSceneY() + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        });
        scene.addEventFilter(javafx.scene.input.KeyEvent.ANY, e -> {
            try { java.nio.file.Files.writeString(log,
                "KEY " + e.getEventType() + " " + e.getCode() + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        });

        primaryStage.setScene(scene);
        primaryStage.show();
        primaryStage.requestFocus();
        javafx.application.Platform.runLater(() -> WindowsDarkMode.apply(GlobalPrefs.isDarkMode()));
    }

    public static void openProject(Stage stage, Path dbPath) {
        try {
            AppContext.reset();
            SQLiteConnection.reset();
            System.setProperty("vulntriage.db.url", "jdbc:sqlite:" + dbPath);
            AppContext.getInstance();
            MainWindow window = new MainWindow(stage);
            window.show();
        } catch (Exception e) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("Failed to Open Project");
            alert.setHeaderText("Could not open: " + dbPath.getFileName());
            alert.setContentText(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            alert.showAndWait();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
