package com.vulntriage.repository.sqlite;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.FinalReview;
import com.vulntriage.domain.enums.FinalVerdict;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.repository.api.FinalReviewRepository;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SQLiteFinalReviewRepository implements FinalReviewRepository {

    private final Connection conn;

    public SQLiteFinalReviewRepository() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    @Override
    public void save(FinalReview r) {
        String sql = "INSERT INTO final_reviews (finding_id, verdict, notes, reviewed_at) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong  (1, r.getFindingId());
            ps.setString(2, r.getVerdict().name());
            ps.setString(3, r.getNotes());
            ps.setString(4, LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) r.setId(keys.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save final review", e);
        }
    }

    @Override
    public void update(FinalReview r) {
        String sql = "UPDATE final_reviews SET verdict=?, notes=?, reviewed_at=? WHERE finding_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.getVerdict().name());
            ps.setString(2, r.getNotes());
            ps.setString(3, LocalDateTime.now().toString());
            ps.setLong  (4, r.getFindingId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update final review", e);
        }
    }

    @Override
    public Optional<FinalReview> findByFindingId(long findingId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM final_reviews WHERE finding_id = ?")) {
            ps.setLong(1, findingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find final review", e);
        }
        return Optional.empty();
    }

    @Override
    public List<FinalReview> findAll() {
        List<FinalReview> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM final_reviews");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list final reviews", e);
        }
        return list;
    }

    @Override
    public void deleteByFindingId(long findingId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM final_reviews WHERE finding_id = ?")) {
            ps.setLong(1, findingId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete final review", e);
        }
    }

    @Override
    public boolean isFlagged(long findingId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM case_flags WHERE finding_id = ?")) {
            ps.setLong(1, findingId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check flag", e);
        }
    }

    @Override
    public void setFlagged(long findingId, boolean flagged) {
        try {
            if (flagged) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO case_flags (finding_id) VALUES (?)")) {
                    ps.setLong(1, findingId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM case_flags WHERE finding_id = ?")) {
                    ps.setLong(1, findingId);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set flag", e);
        }
    }

    @Override
    public List<Finding> findQualifyingFindings() {
        String sql = """
            SELECT f.* FROM findings f
            JOIN manual_reviews mr ON mr.finding_id = f.id
            WHERE mr.verdict = 'TP'
            ORDER BY f.severity, f.rule_id
            """;
        List<Finding> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapFinding(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query qualifying findings", e);
        }
        return list;
    }

    private FinalReview map(ResultSet rs) throws SQLException {
        FinalReview r = new FinalReview();
        r.setId       (rs.getLong  ("id"));
        r.setFindingId(rs.getLong  ("finding_id"));
        r.setVerdict  (FinalVerdict.fromName(rs.getString("verdict")));
        r.setNotes    (rs.getString("notes"));
        String at = rs.getString("reviewed_at");
        if (at != null) r.setReviewedAt(LocalDateTime.parse(at));
        return r;
    }

    private Finding mapFinding(ResultSet rs) throws SQLException {
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
