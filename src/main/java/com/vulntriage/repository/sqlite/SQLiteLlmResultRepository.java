package com.vulntriage.repository.sqlite;

import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.LlmResultRepository;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SQLiteLlmResultRepository implements LlmResultRepository {

    private final Connection conn;

    public SQLiteLlmResultRepository() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    @Override
    public void save(LlmResult r) {
        String sql = """
            INSERT INTO llm_results
              (finding_id, evaluation_run_id, llm_verdict, confidence,
               reasoning, remediation, model_used, prompt_version, created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong  (1, r.getFindingId());
            ps.setLong  (2, r.getEvaluationRunId());
            ps.setString(3, r.getLlmVerdict().name());
            ps.setInt   (4, r.getConfidence());
            ps.setString(5, r.getReasoning());
            ps.setString(6, r.getRemediation());
            ps.setString(7, r.getModelUsed());
            ps.setString(8, r.getPromptVersion());
            ps.setString(9, LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) r.setId(keys.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save LLM result", e);
        }
    }

    @Override
    public Optional<LlmResult> findByFindingIdAndRunId(long findingId, long runId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM llm_results WHERE finding_id=? AND evaluation_run_id=?")) {
            ps.setLong(1, findingId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find LLM result", e);
        }
        return Optional.empty();
    }

    @Override
    public List<LlmResult> findByEvaluationRunId(long evaluationRunId) {
        List<LlmResult> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM llm_results WHERE evaluation_run_id=?")) {
            ps.setLong(1, evaluationRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find LLM results for run", e);
        }
        return list;
    }

    @Override
    public long countByEvaluationRunId(long evaluationRunId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM llm_results WHERE evaluation_run_id=?")) {
            ps.setLong(1, evaluationRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count LLM results", e);
        }
    }

    @Override
    public boolean existsByFindingId(long findingId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM llm_results WHERE finding_id=? LIMIT 1")) {
            ps.setLong(1, findingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check LLM result for finding", e);
        }
    }

    @Override
    public Optional<LlmResult> findByFindingId(long findingId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM llm_results WHERE finding_id=? ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, findingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find LLM result for finding", e);
        }
        return Optional.empty();
    }

    @Override
    public void deleteByFindingId(long findingId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM llm_results WHERE finding_id=?")) {
            ps.setLong(1, findingId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete LLM result for finding", e);
        }
    }

    private LlmResult map(ResultSet rs) throws SQLException {
        LlmResult r = new LlmResult();
        r.setId              (rs.getLong  ("id"));
        r.setFindingId       (rs.getLong  ("finding_id"));
        r.setEvaluationRunId (rs.getLong  ("evaluation_run_id"));
        r.setLlmVerdict      (Verdict.valueOf(rs.getString("llm_verdict")));
        r.setConfidence      (rs.getInt   ("confidence"));
        r.setReasoning       (rs.getString("reasoning"));
        r.setRemediation     (rs.getString("remediation"));
        r.setModelUsed       (rs.getString("model_used"));
        r.setPromptVersion   (rs.getString("prompt_version"));
        String ca = rs.getString("created_at");
        if (ca != null) r.setCreatedAt(LocalDateTime.parse(ca));
        return r;
    }
}
