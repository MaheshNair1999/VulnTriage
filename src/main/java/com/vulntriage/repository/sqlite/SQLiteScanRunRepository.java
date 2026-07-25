package com.vulntriage.repository.sqlite;

import com.vulntriage.domain.ScanRun;
import com.vulntriage.domain.enums.PipelineStatus;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.repository.api.ScanRunRepository;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SQLiteScanRunRepository implements ScanRunRepository {

    private final Connection conn;

    public SQLiteScanRunRepository() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    @Override
    public void save(ScanRun run) {
        String sql = """
            INSERT INTO scan_runs
              (repository_id, scanner_type, started_at, completed_at, status, findings_count)
            VALUES (?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong  (1, run.getRepositoryId());
            ps.setString(2, run.getScannerType().name());
            ps.setString(3, run.getStartedAt().toString());
            ps.setString(4, run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
            ps.setString(5, run.getStatus().name());
            ps.setInt   (6, run.getFindingsCount());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) run.setId(keys.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save scan run", e);
        }
    }

    @Override
    public Optional<ScanRun> findById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM scan_runs WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find scan run id=" + id, e);
        }
        return Optional.empty();
    }

    @Override
    public List<ScanRun> findByRepositoryId(long repositoryId) {
        List<ScanRun> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM scan_runs WHERE repository_id = ? ORDER BY started_at DESC")) {
            ps.setLong(1, repositoryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find scan runs", e);
        }
        return list;
    }

    @Override
    public void update(ScanRun run) {
        String sql = """
            UPDATE scan_runs
            SET completed_at=?, status=?, findings_count=?
            WHERE id=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
            ps.setString(2, run.getStatus().name());
            ps.setInt   (3, run.getFindingsCount());
            ps.setLong  (4, run.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update scan run id=" + run.getId(), e);
        }
    }

    @Override
    public Optional<ScanRun> findLatest(long repositoryId, ScannerType scannerType) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM scan_runs WHERE repository_id=? AND scanner_type=? " +
                "ORDER BY started_at DESC LIMIT 1")) {
            ps.setLong  (1, repositoryId);
            ps.setString(2, scannerType.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find latest scan run", e);
        }
        return Optional.empty();
    }

    private ScanRun map(ResultSet rs) throws SQLException {
        ScanRun run = new ScanRun();
        run.setId            (rs.getLong  ("id"));
        run.setRepositoryId  (rs.getLong  ("repository_id"));
        run.setScannerType   (ScannerType.valueOf(rs.getString("scanner_type")));
        run.setStatus        (PipelineStatus.valueOf(rs.getString("status")));
        run.setFindingsCount (rs.getInt   ("findings_count"));
        String sa = rs.getString("started_at");
        if (sa != null) run.setStartedAt(LocalDateTime.parse(sa));
        String ca = rs.getString("completed_at");
        if (ca != null) run.setCompletedAt(LocalDateTime.parse(ca));
        return run;
    }
}
