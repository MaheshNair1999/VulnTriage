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
               cwe, fingerprint, created_at, cvss_vector, cvss_score)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
            ps.setString(14, f.getCvssVector());
            if (f.getCvssScore() != null) ps.setDouble(15, f.getCvssScore());
            else                           ps.setNull  (15, Types.REAL);

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

    // ── countByRepositoryId ───────────────────────────────────────────────

    @Override
    public long countByRepositoryId(long repositoryId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM findings WHERE repository_id = ?")) {
            ps.setLong(1, repositoryId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count findings for repository " + repositoryId, e);
        }
    }

    // ── countBySeverity ───────────────────────────────────────────────────

    @Override
    public long countBySeverity(long repositoryId, Severity severity) {
        String sql = repositoryId < 0
            ? "SELECT COUNT(*) FROM findings WHERE severity = ?"
            : "SELECT COUNT(*) FROM findings WHERE repository_id = ? AND severity = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (repositoryId < 0) {
                ps.setString(1, severity.name());
            } else {
                ps.setLong  (1, repositoryId);
                ps.setString(2, severity.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count findings by severity", e);
        }
    }

    // ── findPageFiltered ──────────────────────────────────────────────────

    @Override
    public List<Finding> findPageFiltered(long repositoryId, String severity, String source,
                                          String search, long afterId, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM findings WHERE id > ?");
        params.add(afterId);
        if (repositoryId >= 0) {
            sql.append(" AND repository_id = ?");
            params.add(repositoryId);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = ?");
            params.add(severity.toUpperCase());
        }
        if (source != null && !source.isBlank()) {
            sql.append(" AND source = ?");
            params.add(source.toUpperCase());
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (LOWER(rule_id) LIKE ? OR LOWER(file_path) LIKE ? OR LOWER(category) LIKE ? OR LOWER(message) LIKE ?)");
            String like = "%" + search.toLowerCase() + "%";
            params.add(like); params.add(like); params.add(like); params.add(like);
        }
        sql.append(" ORDER BY id LIMIT ?");
        params.add(limit);
        return query(sql.toString(), params.toArray());
    }

    // ── countFiltered ─────────────────────────────────────────────────────

    @Override
    public long countFiltered(long repositoryId, String severity, String source, String search) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM findings WHERE 1=1");
        if (repositoryId >= 0) {
            sql.append(" AND repository_id = ?");
            params.add(repositoryId);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = ?");
            params.add(severity.toUpperCase());
        }
        if (source != null && !source.isBlank()) {
            sql.append(" AND source = ?");
            params.add(source.toUpperCase());
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (LOWER(rule_id) LIKE ? OR LOWER(file_path) LIKE ? OR LOWER(category) LIKE ? OR LOWER(message) LIKE ?)");
            String like = "%" + search.toLowerCase() + "%";
            params.add(like); params.add(like); params.add(like); params.add(like);
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) ps.setString(i + 1, s);
                else if (p instanceof Long l) ps.setLong(i + 1, l);
                else ps.setObject(i + 1, p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("countFiltered query failed: " + sql, e);
        }
    }

    // ── countByScannerBreakdown ───────────────────────────────────────────

    @Override
    public java.util.Map<String, long[]> countByScannerBreakdown(long repositoryId) {
        String sql = repositoryId < 0
            ? "SELECT source, severity, COUNT(*) AS cnt FROM findings GROUP BY source, severity"
            : "SELECT source, severity, COUNT(*) AS cnt FROM findings WHERE repository_id = ? GROUP BY source, severity";
        java.util.Map<String, long[]> result = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (repositoryId >= 0) ps.setLong(1, repositoryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String src = rs.getString("source");
                    String sev = rs.getString("severity");
                    long cnt   = rs.getLong("cnt");
                    long[] arr = result.computeIfAbsent(src, k -> new long[4]); // [total, errors, warnings, infos]
                    arr[0] += cnt;
                    if ("ERROR".equals(sev))   arr[1] += cnt;
                    else if ("WARNING".equals(sev)) arr[2] += cnt;
                    else                            arr[3] += cnt;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to compute scanner breakdown", e);
        }
        return result;
    }

    // ── findTopRuleIds ────────────────────────────────────────────────────

    @Override
    public java.util.List<String[]> findTopRuleIds(long repositoryId, int limit) {
        String sql = repositoryId < 0
            ? "SELECT rule_id, COUNT(*) AS cnt FROM findings GROUP BY rule_id ORDER BY cnt DESC LIMIT ?"
            : "SELECT rule_id, COUNT(*) AS cnt FROM findings WHERE repository_id = ? GROUP BY rule_id ORDER BY cnt DESC LIMIT ?";
        java.util.List<String[]> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (repositoryId < 0) {
                ps.setInt(1, limit);
            } else {
                ps.setLong(1, repositoryId);
                ps.setInt (2, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{rs.getString(1), String.valueOf(rs.getLong(2))});
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query top rule IDs", e);
        }
        return result;
    }

    // ── findTopFilePaths ──────────────────────────────────────────────────

    @Override
    public java.util.List<String[]> findTopFilePaths(long repositoryId, int limit) {
        String sql = repositoryId < 0
            ? "SELECT file_path, COUNT(*) AS cnt FROM findings GROUP BY file_path ORDER BY cnt DESC LIMIT ?"
            : "SELECT file_path, COUNT(*) AS cnt FROM findings WHERE repository_id = ? GROUP BY file_path ORDER BY cnt DESC LIMIT ?";
        java.util.List<String[]> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (repositoryId < 0) {
                ps.setInt(1, limit);
            } else {
                ps.setLong(1, repositoryId);
                ps.setInt (2, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new String[]{rs.getString(1), String.valueOf(rs.getLong(2))});
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query top file paths", e);
        }
        return result;
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
        f.setCvssVector  (rs.getString("cvss_vector"));
        double cvssScore = rs.getDouble("cvss_score");
        f.setCvssScore   (rs.wasNull() ? null : cvssScore);
        return f;
    }
}
