package com.vulntriage.repository.sqlite;

import com.vulntriage.domain.EvaluationRun;
import com.vulntriage.repository.api.EvaluationRepository;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SQLiteEvaluationRepository implements EvaluationRepository {

    private final Connection conn;

    public SQLiteEvaluationRepository() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    @Override
    public void save(EvaluationRun run) {
        String sql = "INSERT INTO evaluation_runs (name, description, created_at) VALUES (?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, run.getName());
            ps.setString(2, run.getDescription());
            ps.setString(3, LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) run.setId(keys.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save evaluation run", e);
        }
    }

    @Override
    public Optional<EvaluationRun> findById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM evaluation_runs WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find evaluation run", e);
        }
        return Optional.empty();
    }

    @Override
    public List<EvaluationRun> findAll() {
        List<EvaluationRun> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM evaluation_runs ORDER BY created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list evaluation runs", e);
        }
        return list;
    }

    @Override
    public void saveSampleFinding(long evaluationRunId, long findingId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO evaluation_samples (evaluation_run_id, finding_id) VALUES (?,?)")) {
            ps.setLong(1, evaluationRunId);
            ps.setLong(2, findingId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save sample finding", e);
        }
    }

    @Override
    public List<Long> findSampleFindingIds(long evaluationRunId) {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT finding_id FROM evaluation_samples WHERE evaluation_run_id=?")) {
            ps.setLong(1, evaluationRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("finding_id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find sample findings", e);
        }
        return ids;
    }

    @Override
    public void deleteById(long id) {
        // Delete child rows first to satisfy foreign-key constraints
        try (PreparedStatement p1 = conn.prepareStatement(
                "DELETE FROM llm_results WHERE evaluation_run_id = ?");
             PreparedStatement p2 = conn.prepareStatement(
                "DELETE FROM evaluation_samples WHERE evaluation_run_id = ?");
             PreparedStatement p3 = conn.prepareStatement(
                "DELETE FROM evaluation_runs WHERE id = ?")) {
            p1.setLong(1, id); p1.executeUpdate();
            p2.setLong(1, id); p2.executeUpdate();
            p3.setLong(1, id); p3.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete evaluation run", e);
        }
    }

    private EvaluationRun map(ResultSet rs) throws SQLException {
        EvaluationRun run = new EvaluationRun();
        run.setId         (rs.getLong  ("id"));
        run.setName       (rs.getString("name"));
        run.setDescription(rs.getString("description"));
        String ca = rs.getString("created_at");
        if (ca != null) run.setCreatedAt(LocalDateTime.parse(ca));
        return run;
    }
}
