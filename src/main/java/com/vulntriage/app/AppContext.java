package com.vulntriage.app;

import com.vulntriage.config.AppConfig;
import com.vulntriage.config.GlobalPrefs;
import com.vulntriage.config.ThemeColors;
import com.vulntriage.repository.CachingFindingRepository;
import com.vulntriage.repository.api.WorkflowRepository;
import com.vulntriage.repository.api.PromptTemplateRepository;
import com.vulntriage.repository.sqlite.SQLiteWorkflowRepository;
import com.vulntriage.repository.sqlite.SQLitePromptTemplateRepository;
import com.vulntriage.repository.api.*;
import com.vulntriage.repository.sqlite.*;
import com.vulntriage.triage.api.TriageStrategy;
import com.vulntriage.triage.mock.MockTriageStrategy;
import com.vulntriage.triage.ollama.OllamaTriageStrategy;

/**
 * Application-wide service locator.
 *
 * Holds singleton instances of all repositories and services.
 * UI controllers get their dependencies from here rather than
 * instantiating them directly — makes testing easier and keeps
 * the dependency graph explicit.
 *
 * In a larger application this would be replaced by a DI framework
 * (Spring, Guice). For this project a manual singleton is appropriate.
 */
public class AppContext {

    private static AppContext instance;

    // ── Repositories ───────────────────────────────────────────────────────
    private final RepositoryRepo           repositoryRepo;
    private final FindingRepository        findingRepo;
    private final ScanRunRepository        scanRunRepo;
    private final ManualReviewRepository   reviewRepo;
    private final LlmResultRepository      llmRepo;
    private final EvaluationRepository     evalRepo;
    private final WorkflowRepository       workflowRepo;
    private final PromptTemplateRepository promptTemplateRepo;

    // ── Services ───────────────────────────────────────────────────────────
    private TriageStrategy triageStrategy;

    // ── Config ─────────────────────────────────────────────────────────────
    private String ollamaUrl;
    private String ollamaModel;

    private AppContext() {
        repositoryRepo     = new SQLiteRepositoryRepo();
        findingRepo        = new CachingFindingRepository(new SQLiteFindingRepository());
        scanRunRepo        = new SQLiteScanRunRepository();
        reviewRepo         = new SQLiteManualReviewRepository();
        llmRepo            = new SQLiteLlmResultRepository();
        evalRepo           = new SQLiteEvaluationRepository();
        workflowRepo       = new SQLiteWorkflowRepository();
        promptTemplateRepo = new SQLitePromptTemplateRepository();
        triageStrategy = new MockTriageStrategy(); // safe default — switch to Ollama in Settings

        // Load persisted settings
        AppConfig cfg = AppConfig.getInstance();
        ollamaUrl   = cfg.getOllamaUrl();
        ollamaModel = cfg.getOllamaModel();

        // Load and apply persisted theme (global prefs, not per-project DB)
        boolean dark = GlobalPrefs.isDarkMode();
        ThemeColors.apply(dark);
        darkMode.set(dark);

        // Auto-enable Ollama if settings were previously saved
        // Without this, triageStrategy stays as Mock even after app restart
        this.triageStrategy = new OllamaTriageStrategy(ollamaUrl, ollamaModel);
    }

    public static synchronized AppContext getInstance() {
        if (instance == null) instance = new AppContext();
        return instance;
    }

    /** Clear the singleton so the next getInstance() re-initialises against the current DB. */
    public static synchronized void reset() {
        instance = null;
    }

    // ── Repository accessors ───────────────────────────────────────────────

    public RepositoryRepo           repositoryRepo()     { return repositoryRepo; }
    public FindingRepository        findingRepo()        { return findingRepo; }
    public ScanRunRepository        scanRunRepo()        { return scanRunRepo; }
    public ManualReviewRepository   reviewRepo()         { return reviewRepo; }
    public LlmResultRepository      llmRepo()            { return llmRepo; }
    public EvaluationRepository     evalRepo()           { return evalRepo; }
    public WorkflowRepository       workflowRepo()       { return workflowRepo; }
    public PromptTemplateRepository promptTemplateRepo() { return promptTemplateRepo; }

    // ── Triage strategy ────────────────────────────────────────────────────

    public TriageStrategy triageStrategy() { return triageStrategy; }

    /** Switch to real Ollama — called from Settings screen */
    public void enableOllama(String url, String model) {
        String cleanUrl = url.endsWith("/") ? url.replaceAll("/+$", "") : url;
        this.ollamaUrl   = cleanUrl;
        this.ollamaModel = model;
        this.triageStrategy = new OllamaTriageStrategy(cleanUrl, model);
        AppConfig.getInstance().saveOllamaSettings(cleanUrl, model);
    }

    /** Switch back to mock — useful during development/testing */
    public void enableMock() {
        this.triageStrategy = new MockTriageStrategy();
    }

    // ── Triage state (observable so sidebar can show indicator) ───────────
    private final javafx.beans.property.BooleanProperty triageRunning =
        new javafx.beans.property.SimpleBooleanProperty(false);

    public javafx.beans.property.BooleanProperty triageRunningProperty() {
        return triageRunning;
    }
    public boolean isTriageRunning() { return triageRunning.get(); }
    public void setTriageRunning(boolean running) {
        javafx.application.Platform.runLater(() -> triageRunning.set(running));
    }

    // ── Workflow state ─────────────────────────────────────────────────────
    private final javafx.beans.property.BooleanProperty workflowRunning =
        new javafx.beans.property.SimpleBooleanProperty(false);

    public javafx.beans.property.BooleanProperty workflowRunningProperty() {
        return workflowRunning;
    }
    public boolean isWorkflowRunning() { return workflowRunning.get(); }
    public void setWorkflowRunning(boolean running) {
        javafx.application.Platform.runLater(() -> workflowRunning.set(running));
    }

    // ── Dark mode state ────────────────────────────────────────────────────
    private final javafx.beans.property.BooleanProperty darkMode =
        new javafx.beans.property.SimpleBooleanProperty(false);

    public javafx.beans.property.BooleanProperty darkModeProperty() { return darkMode; }
    public boolean isDarkMode() { return darkMode.get(); }
    public void setDarkMode(boolean dark) {
        ThemeColors.apply(dark);
        GlobalPrefs.saveDarkMode(dark);
        javafx.application.Platform.runLater(() -> darkMode.set(dark));
    }

    // ── Scan state ─────────────────────────────────────────────────────────
    private final javafx.beans.property.BooleanProperty scanRunning =
        new javafx.beans.property.SimpleBooleanProperty(false);

    public javafx.beans.property.BooleanProperty scanRunningProperty() {
        return scanRunning;
    }
    public boolean isScanRunning() { return scanRunning.get(); }
    public void setScanRunning(boolean running) {
        javafx.application.Platform.runLater(() -> scanRunning.set(running));
    }

    public String getOllamaUrl()   { return ollamaUrl; }
    public String getOllamaModel() { return ollamaModel; }
}
