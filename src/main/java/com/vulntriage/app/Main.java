package com.vulntriage.app;

import com.vulntriage.repository.sqlite.SQLiteConnection;
import com.vulntriage.ui.MainWindow;
import com.vulntriage.ui.ProjectSelectorView;
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
        primaryStage.setScene(selector.build(primaryStage));
        primaryStage.show();
    }

    public static void openProject(Stage stage, Path dbPath) {
        // Reset singletons before setting the new URL — safe on both first launch and project switch
        AppContext.reset();
        SQLiteConnection.reset();
        System.setProperty("vulntriage.db.url", "jdbc:sqlite:" + dbPath);
        AppContext.getInstance();

        MainWindow window = new MainWindow(stage);
        window.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
