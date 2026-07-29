package com.vulntriage.repository.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages the single SQLite connection for the application.
 *
 * On first use it creates the database file and initialises the full schema.
 * Uses a singleton pattern — one connection shared across the application.
 *
 * If the project later needs to switch to PostgreSQL, replace getConnection()
 * with a connection pool (e.g. HikariCP) and update the JDBC URL.
 */
public class SQLiteConnection {

    private static final Logger log = LoggerFactory.getLogger(SQLiteConnection.class);

    private static SQLiteConnection instance;
    private Connection connection;

    private SQLiteConnection() {
        // Read at construction time so a reset() + new instance picks up a changed system property
        String dbUrl = System.getProperty("vulntriage.db.url", "jdbc:sqlite:vulntriage.db");
        try {
            connection = DriverManager.getConnection(dbUrl);
            connection.createStatement().execute("PRAGMA foreign_keys = ON");
            log.info("Connected to SQLite database: {}", dbUrl);
            initialiseSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to SQLite database", e);
        }
    }

    public static synchronized SQLiteConnection getInstance() {
        if (instance == null) {
            instance = new SQLiteConnection();
        }
        return instance;
    }

    /** Close the current connection and clear the singleton so the next getInstance() reconnects. */
    public static synchronized void reset() {
        if (instance != null) {
            try { instance.connection.close(); } catch (SQLException ignored) {}
            instance = null;
        }
    }

    public Connection getConnection() {
        return connection;
    }

    // ── Schema ─────────────────────────────────────────────────────────────

    private void initialiseSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS repositories (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    name         TEXT    NOT NULL,
                    local_path   TEXT    NOT NULL,
                    url          TEXT,
                    created_at   TEXT    NOT NULL,
                    last_scanned TEXT
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS scan_runs (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    repository_id  INTEGER NOT NULL REFERENCES repositories(id),
                    scanner_type   TEXT    NOT NULL,
                    started_at     TEXT    NOT NULL,
                    completed_at   TEXT,
                    status         TEXT    NOT NULL DEFAULT 'RUNNING',
                    findings_count INTEGER NOT NULL DEFAULT 0
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS findings (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    scan_run_id   INTEGER NOT NULL REFERENCES scan_runs(id),
                    repository_id INTEGER NOT NULL REFERENCES repositories(id),
                    source        TEXT    NOT NULL,
                    rule_id       TEXT    NOT NULL,
                    file_path     TEXT    NOT NULL,
                    line_number   INTEGER,
                    severity      TEXT    NOT NULL,
                    category      TEXT    NOT NULL,
                    message       TEXT    NOT NULL,
                    code_snippet  TEXT,
                    cwe           TEXT,
                    fingerprint   TEXT    NOT NULL,
                    created_at    TEXT    NOT NULL,
                    cvss_vector   TEXT,
                    cvss_score    REAL
                )
            """);

            stmt.executeUpdate("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_findings_fingerprint
                ON findings(fingerprint)
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS manual_reviews (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    finding_id  INTEGER NOT NULL UNIQUE REFERENCES findings(id),
                    verdict     TEXT    NOT NULL,
                    notes       TEXT,
                    reviewed_at TEXT    NOT NULL
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS evaluation_runs (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT NOT NULL,
                    description TEXT,
                    created_at  TEXT NOT NULL
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS evaluation_samples (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    evaluation_run_id INTEGER NOT NULL REFERENCES evaluation_runs(id),
                    finding_id        INTEGER NOT NULL REFERENCES findings(id),
                    UNIQUE(evaluation_run_id, finding_id)
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS llm_results (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    finding_id        INTEGER NOT NULL REFERENCES findings(id),
                    evaluation_run_id INTEGER REFERENCES evaluation_runs(id),
                    llm_verdict       TEXT    NOT NULL,
                    confidence        INTEGER NOT NULL,
                    reasoning         TEXT,
                    remediation       TEXT,
                    model_used        TEXT    NOT NULL,
                    prompt_version    TEXT    NOT NULL,
                    created_at        TEXT    NOT NULL
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS config (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS workflows (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    name            TEXT NOT NULL,
                    description     TEXT,
                    definition_json TEXT NOT NULL,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL
                )
            """);

            log.info("Database schema initialised successfully");
        }
        migrateSchema();
    }

    /**
     * Applies incremental schema migrations for existing databases.
     * Safe to run on every startup — each ALTER is guarded by a column existence check.
     */
    private void migrateSchema() {
        try (Statement stmt = connection.createStatement()) {
            // Migration: add cwe column if missing (added in week-10 for thesis dataset)
            boolean hasCwe = connection.getMetaData()
                .getColumns(null, null, "findings", "cwe")
                .next();
            if (!hasCwe) {
                stmt.executeUpdate("ALTER TABLE findings ADD COLUMN cwe TEXT");
                log.info("Schema migration: added 'cwe' column to findings table");
            }

            // Migration: add hidden flag for soft-delete on repositories
            boolean hasHidden = connection.getMetaData()
                .getColumns(null, null, "repositories", "hidden")
                .next();
            if (!hasHidden) {
                stmt.executeUpdate("ALTER TABLE repositories ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0");
                log.info("Schema migration: added 'hidden' column to repositories table");
            }

            // Migration: add CVSS v3.1 fields to findings (added for CVSS calculator feature)
            boolean hasCvssVector = connection.getMetaData()
                .getColumns(null, null, "findings", "cvss_vector")
                .next();
            if (!hasCvssVector) {
                stmt.executeUpdate("ALTER TABLE findings ADD COLUMN cvss_vector TEXT");
                log.info("Schema migration: added 'cvss_vector' column to findings table");
            }
            boolean hasCvssScore = connection.getMetaData()
                .getColumns(null, null, "findings", "cvss_score")
                .next();
            if (!hasCvssScore) {
                stmt.executeUpdate("ALTER TABLE findings ADD COLUMN cvss_score REAL");
                log.info("Schema migration: added 'cvss_score' column to findings table");
            }
        } catch (SQLException e) {
            log.warn("Schema migration check failed (non-fatal): {}", e.getMessage());
        }
    }
}
