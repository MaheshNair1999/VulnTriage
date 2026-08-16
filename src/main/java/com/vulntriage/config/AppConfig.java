package com.vulntriage.config;

import com.vulntriage.repository.sqlite.SQLiteConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Reads and writes application configuration persisted in the SQLite config table.
 *
 * Keys are simple strings. Values are stored as text.
 * This allows settings (Ollama URL, model name) to survive app restarts.
 */
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    public static final String OLLAMA_URL    = "ollama.url";
    public static final String OLLAMA_MODEL  = "ollama.model";
    public static final String TRIAGE_DELAY  = "triage.delay_ms";
    public static final String DARK_MODE     = "app.dark_mode";

    private static final String DEFAULT_OLLAMA_URL   = "http://localhost:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:3b";
    private static final String DEFAULT_TRIAGE_DELAY = "0";

    private static AppConfig instance;
    private final Connection conn;

    private AppConfig() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    public static synchronized AppConfig getInstance() {
        if (instance == null) instance = new AppConfig();
        return instance;
    }

    public String get(String key, String defaultValue) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM config WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (Exception e) {
            log.warn("Failed to read config key '{}': {}", key, e.getMessage());
        }
        return defaultValue;
    }

    public void set(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO config (key, value) VALUES (?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Failed to write config key '{}': {}", key, e.getMessage());
        }
    }

    public String getOllamaUrl()    { return get(OLLAMA_URL,   DEFAULT_OLLAMA_URL); }
    public String getOllamaModel()  { return get(OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL); }
    public int    getTriageDelayMs() {
        try { return Integer.parseInt(get(TRIAGE_DELAY, DEFAULT_TRIAGE_DELAY)); }
        catch (NumberFormatException e) { return 0; }
    }

    public void saveOllamaSettings(String url, String model) {
        set(OLLAMA_URL,   url);
        set(OLLAMA_MODEL, model);
        log.info("Saved Ollama settings: url={}, model={}", url, model);
    }

    public void saveTriageDelay(int delayMs) {
        set(TRIAGE_DELAY, String.valueOf(delayMs));
        log.info("Saved triage delay: {}ms", delayMs);
    }

    public boolean isDarkMode() {
        return "true".equals(get(DARK_MODE, "false"));
    }

    public void saveDarkMode(boolean dark) {
        set(DARK_MODE, String.valueOf(dark));
    }
}
