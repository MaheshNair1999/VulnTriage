package com.vulntriage.repository.sqlite;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.FindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SQLiteFindingRepository implements FindingRepository {

    private static final Logger log = LoggerFactory.getLogger(SQLiteFindingRepository.class);
    private final Connection conn;

    public SQLiteFindingRepository() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    // ── save ──────────────────────────────────────────────────────────────

    @Override
    public void save(Finding f) {
        String sql = """
            INSERT OR IGNORE INTO findings
              (scan_run_id, repository_id, source, rule_id, file_path,
               line_number, severity, category, message, code_snippet,
               cwe, fingerprint, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong  (1,  f.getScanRunId());
            ps.setLong  (2,  f.getRepositoryId());
            ps.setString(3,  f.getSource().name());
            ps.setString(4,  f.getRuleId());
            ps.setString(5,  f.getFilePath());
            if (f.getLineNumber() != null) ps.setInt(6, f.getLineNumber());
            else                            ps.setNull(6, Types.INTEGER);
            ps.setString(7,  f.getSeverity().name());
            ps.setString(8,  f.getCategory());
            ps.setString(9,  f.getMessage());
            ps.setString(10, f.getCodeSnippet());
            ps.setString(11, f.getCwe());
            ps.setString(12, f.getFingerprint());
            ps.setString(13, LocalDateTime.now().toString());

            int rows = ps.executeUpdate();
            if (rows > 0) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) f.setId(keys.getLong(1));
                }
            } else {
                // Row already exists (INSERT OR IGNORE skipped it) — fetch the existing ID
                try (PreparedStatement sel = conn.prepareStatement(
                        "SELECT id FROM findings WHERE fingerprint = ?")) {
                    sel.setString(1, f.getFingerprint());
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) f.setId(rs.getLong(1));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to save finding: {}", f, e);
            throw new RuntimeException("Failed to save finding", e);
        }
    }

    // ── findById ──────────────────────────────────────────────────────────

    @Override
    public Optional<Finding> findById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM findings WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find finding by id=" + id, e);
        }
        return Optional.empty();
    }

    // ── findByRepositoryId ────────────────────────────────────────────────

    @Override
    public List<Finding> findByRepositoryId(long repositoryId) {
        return query("SELECT * FROM findings WHERE repository_id = ?", repositoryId);
    }

    // ── findByScanRunId ───────────────────────────────────────────────────

    @Override
    public List<Finding> findByScanRunId(long scanRunId) {
        return query("SELECT * FROM findings WHERE scan_run_id = ?", scanRunId);
    }

    // ── findUnreviewed ────────────────────────────────────────────────────

    @Override
    public List<Finding> findUnreviewed() {
        String sql = """
            SELECT f.* FROM findings f
            LEFT JOIN manual_reviews mr ON f.id = mr.finding_id
            WHERE mr.id IS NULL
            """;
        return query(sql);
    }

    // ── findBySeverity ────────────────────────────────────────────────────

    @Override
    public List<Finding> findBySeverity(Severity severity) {
        return query("SELECT * FROM findings WHERE severity = ?", severity.name());
    }

    // ── findByCategory ────────────────────────────────────────────────────

    @Override
    public List<Finding> findByCategory(String category) {
        return query("SELECT * FROM findings WHERE category = ?", category);
    }

    // ── existsByFingerprint ───────────────────────────────────────────────

    @Override
    public boolean existsByFingerprint(String fingerprint) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM findings WHERE fingerprint = ? LIMIT 1")) {
            ps.setString(1, fingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check fingerprint", e);
        }
    }

    // ── counts ────────────────────────────────────────────────────────────

    @Override
    public long countAll() {
        return count("SELECT COUNT(*) FROM findings");
    }

    @Override
    public long countByVerdict(Verdict verdict) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM manual_reviews WHERE verdict = ?")) {
            ps.setString(1, verdict.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count by verdict", e);
        }
    }

    @Override
    public long countUnreviewed() {
        return count("""
            SELECT COUNT(*) FROM findings f
            LEFT JOIN manual_reviews mr ON f.id = mr.finding_id
            WHERE mr.id IS NULL
            """);
    }

    // ── deleteByScanRunId ─────────────────────────────────────────────────

    @Override
    public void deleteByScanRunId(long scanRunId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM findings WHERE scan_run_id = ?")) {
            ps.setLong(1, scanRunId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete findings for scan run " + scanRunId, e);
        }
    }

    // ── deleteById ────────────────────────────────────────────────────────

    @Override
    public void deleteById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM findings WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete finding " + id, e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private List<Finding> query(String sql, Object... params) {
        List<Finding> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof String s) ps.setString(i + 1, s);
                else if (params[i] instanceof Long l) ps.setLong(i + 1, l);
                else ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
        return results;
    }

    private long count(String sql) {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Count query failed", e);
        }
    }

    private Finding map(ResultSet rs) throws SQLException {
        Finding f = new Finding();
        f.setId          (rs.getLong  ("id"));
        f.setScanRunId   (rs.getLong  ("scan_run_id"));
        f.setRepositoryId(rs.getLong  ("repository_id"));
        f.setSource      (ScannerType.valueOf(rs.getString("source")));
        f.setRuleId      (rs.getString("rule_id"));
        f.setFilePath    (rs.getString("file_path"));
        int line = rs.getInt("line_number");
        f.setLineNumber  (rs.wasNull() ? null : line);
        f.setSeverity    (Severity.valueOf(rs.getString("severity")));
        f.setCategory    (rs.getString("category"));
        f.setMessage     (rs.getString("message"));
        f.setCodeSnippet (rs.getString("code_snippet"));
        f.setCwe         (rs.getString("cwe"));
        f.setFingerprint (rs.getString("fingerprint"));
        String ca = rs.getString("created_at");
        if (ca != null) f.setCreatedAt(LocalDateTime.parse(ca));
        return f;
    }
}
