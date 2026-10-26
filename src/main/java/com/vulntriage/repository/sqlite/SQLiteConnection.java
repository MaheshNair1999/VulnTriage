package com.vulntriage.repository.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
            // Set busy_timeout first so subsequent pragmas wait for locks instead of failing
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA busy_timeout=30000");
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA synchronous=FULL");
            }
            // WAL mode allows concurrent reads during writes; non-fatal if it can't be set
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
            } catch (SQLException walEx) {
                log.warn("Could not enable WAL mode (non-fatal): {}", walEx.getMessage());
            }
            // Log the actual journal mode so startup logs confirm what is active
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                if (rs.next()) log.info("SQLite journal mode: {}", rs.getString(1));
            } catch (SQLException ignored) {}
            log.info("Connected to SQLite database: {}", dbUrl);
            initialiseSchema();
            checkpoint();
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
            instance.checkpoint();
            try { instance.connection.close(); } catch (SQLException ignored) {}
            instance = null;
        }
    }

    public Connection getConnection() {
        return connection;
    }

    /**
     * Force a WAL checkpoint so all pending writes are flushed to the main .db file.
     * Safe to call in any journal mode — PRAGMA wal_checkpoint is a no-op in non-WAL mode.
     */
    public void checkpoint() {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(FULL)");
        } catch (SQLException e) {
            log.warn("WAL checkpoint failed (non-fatal): {}", e.getMessage());
        }
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
                    version      TEXT,
                    created_at   TEXT    NOT NULL,
                    last_scanned TEXT,
                    hidden       INTEGER NOT NULL DEFAULT 0
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

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS prompt_templates (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT    NOT NULL,
                    version     TEXT    NOT NULL UNIQUE,
                    description TEXT,
                    template    TEXT    NOT NULL,
                    built_in    INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT    NOT NULL
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS final_reviews (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    finding_id  INTEGER NOT NULL UNIQUE REFERENCES findings(id),
                    verdict     TEXT    NOT NULL,
                    notes       TEXT,
                    reviewed_at TEXT    NOT NULL
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS case_flags (
                    finding_id INTEGER NOT NULL UNIQUE REFERENCES findings(id)
                )
            """);

            log.info("Database schema initialised successfully");
        }
        migrateSchema();
        seedBuiltInPrompts();
    }

    /**
     * Applies incremental schema migrations for existing databases.
     * Safe to run on every startup — each ALTER is guarded by a column existence check.
     */
    private void migrateSchema() {
        // Each migration runs independently — a failure in one does not block the rest.
        runMigration("prompt_templates table", () -> {
            if (!tableExists("prompt_templates")) {
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS prompt_templates (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            name        TEXT    NOT NULL,
                            version     TEXT    NOT NULL UNIQUE,
                            description TEXT,
                            template    TEXT    NOT NULL,
                            built_in    INTEGER NOT NULL DEFAULT 0,
                            created_at  TEXT    NOT NULL
                        )
                    """);
                }
                log.info("Schema migration: created prompt_templates table");
                seedBuiltInPrompts();
            }
        });
        runMigration("cwe column", () -> {
            if (!columnExists("findings", "cwe"))
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate("ALTER TABLE findings ADD COLUMN cwe TEXT");
                }
        });
        runMigration("hidden column", () -> {
            if (!columnExists("repositories", "hidden")) {
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate(
                        "ALTER TABLE repositories ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0");
                }
                log.info("Schema migration: added 'hidden' column to repositories table");
            }
        });
        runMigration("version column", () -> {
            if (!columnExists("repositories", "version"))
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate("ALTER TABLE repositories ADD COLUMN version TEXT");
                }
        });
        runMigration("cvss_vector column", () -> {
            if (!columnExists("findings", "cvss_vector"))
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate("ALTER TABLE findings ADD COLUMN cvss_vector TEXT");
                }
        });
        runMigration("cvss_score column", () -> {
            if (!columnExists("findings", "cvss_score"))
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate("ALTER TABLE findings ADD COLUMN cvss_score REAL");
                }
        });
        runMigration("performance indexes", () -> {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_findings_repository_id ON findings(repository_id)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_findings_severity ON findings(severity)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_findings_source ON findings(source)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_findings_scan_run_id ON findings(scan_run_id)");
            }
            log.info("Schema migration: ensured performance indexes on findings table");
        });
        runMigration("final_reviews table", () -> {
            if (!tableExists("final_reviews"))
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS final_reviews (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            finding_id  INTEGER NOT NULL UNIQUE REFERENCES findings(id),
                            verdict     TEXT    NOT NULL,
                            notes       TEXT,
                            reviewed_at TEXT    NOT NULL
                        )
                    """);
                }
        });
        runMigration("case_flags table", () -> {
            if (!tableExists("case_flags"))
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS case_flags (
                            finding_id INTEGER NOT NULL UNIQUE REFERENCES findings(id)
                        )
                    """);
                }
        });
    }

    @FunctionalInterface
    private interface MigrationStep { void run() throws SQLException; }

    private void runMigration(String name, MigrationStep step) {
        try {
            step.run();
        } catch (SQLException e) {
            log.warn("Schema migration '{}' failed (non-fatal): {}", name, e.getMessage());
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    /**
     * Insert the two built-in prompt templates (v1.0 snippet-only, v2.0 context-enriched)
     * if they do not already exist. Safe to run on every startup — the UNIQUE constraint
     * on `version` prevents duplicates and INSERT OR IGNORE handles the conflict silently.
     */
    private void seedBuiltInPrompts() {
        String v1Template = """
            You are a senior application security engineer reviewing a static analysis finding.

            Your task is to determine whether this finding is a genuine vulnerability.

            Analyze the following Semgrep finding carefully:

            Rule ID  : {{rule_id}}
            Severity : {{severity}}
            Category : {{category}}
            Message  : {{message}}
            Code     : {{code_snippet}}

            Instructions:
            1. Decide if this is a True Positive (TP), False Positive (FP), or needs Review (REVIEW).
               - TP: the code is genuinely vulnerable and exploitable.
               - FP: the code is safe — the rule fired incorrectly (e.g. framework handles escaping).
               - REVIEW: insufficient context to decide with confidence.
            2. Assign a confidence score from 0 to 100.
               Note: this is your subjective certainty, not a calibrated probability.
            3. Briefly explain your reasoning (1-3 sentences).
            4. If TP, suggest a concrete remediation. If FP, explain why it is safe.

            Return ONLY valid JSON — no preamble, no markdown, no explanation outside the JSON:
            {
              "llm_verdict": "TP" or "FP" or "REVIEW",
              "llm_confidence": <integer 0-100>,
              "llm_reasoning": "<your reasoning>",
              "llm_remediation": "<fix suggestion or explanation>"
            }
            """;

        String v2Template = """
            /no_think
            You are a senior application security engineer. Classify this static analysis finding.

            IMPORTANT: Output ONLY the JSON object below. No markdown, no prose, no explanation outside the JSON.

            === FINDING ===
            Rule ID  : {{rule_id}}
            Severity : {{severity}}
            Category : {{category}}
            Message  : {{message}}
            Flagged line ({{line_number}}):
            {{code_snippet}}

            === SOURCE CONTEXT ({{file_path}}) ===
            {{source_context}}

            Use the source context to check:
            - Is Django auto-escaping active? (no |safe filter, no autoescape off)
            - Is the variable user-controlled or from a trusted/framework source?
            - Is there login_required or permission checks restricting access?
            - Is this test code (setUp, test helpers) rather than production code?

            Verdict options: TP (genuinely exploitable), FP (safe in context), REVIEW (still uncertain).

            Return ONLY this JSON — start with { and end with }, nothing else:
            {
              "llm_verdict": "TP" or "FP" or "REVIEW",
              "llm_confidence": <integer 0-100>,
              "llm_reasoning": "<1-2 sentences referencing specific context>",
              "llm_remediation": "<fix if TP, or why safe if FP>"
            }
            """;

        String sql = """
            INSERT OR IGNORE INTO prompt_templates (name, version, description, template, built_in, created_at)
            VALUES (?, ?, ?, ?, 1, ?)
            """;
        String now = java.time.LocalDateTime.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "Snippet-Only Prompt");
            ps.setString(2, "v1.0");
            ps.setString(3, "Original prompt — sends only the 7-line code snippet. Fast, uses minimal context.");
            ps.setString(4, v1Template);
            ps.setString(5, now);
            ps.executeUpdate();

            ps.setString(1, "Context-Enriched Prompt");
            ps.setString(2, "v2.0");
            ps.setString(3, "Context-enriched prompt — sends 80 lines before/after the flagged line. "
                + "Uses {{source_context}} placeholder; requires Repos Base Path to be set in Triage.");
            ps.setString(4, v2Template);
            ps.setString(5, now);
            ps.executeUpdate();

            log.debug("Built-in prompt templates seeded (or already present)");
        } catch (SQLException e) {
            log.warn("Failed to seed built-in prompt templates (non-fatal): {}", e.getMessage());
        }
    }
}
