package com.vulntriage.repository.sqlite;

import com.vulntriage.domain.WorkflowDefinition;
import com.vulntriage.pipeline.WorkflowParser;
import com.vulntriage.repository.api.WorkflowRepository;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite implementation of WorkflowRepository.
 *
 * Each workflow is stored as a JSON blob in the definition_json column.
 * The WorkflowParser handles serialisation and deserialisation.
 *
 * Storing as JSON means we can evolve the workflow format without
 * adding new columns — the schema stays stable.
 */
public class SQLiteWorkflowRepository implements WorkflowRepository {

    private final Connection     conn;
    private final WorkflowParser parser = new WorkflowParser();

    public SQLiteWorkflowRepository() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    @Override
    public void save(WorkflowDefinition def) {
        String sql = """
            INSERT INTO workflows (name, description, definition_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, def.getName());
            ps.setString(2, def.getDescription());
            ps.setString(3, parser.toJson(def));
            ps.setString(4, LocalDateTime.now().toString());
            ps.setString(5, LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) def.setId(keys.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save workflow", e);
        }
    }

    @Override
    public Optional<WorkflowDefinition> findById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflows WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find workflow id=" + id, e);
        }
        return Optional.empty();
    }

    @Override
    public List<WorkflowDefinition> findAll() {
        List<WorkflowDefinition> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflows ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list workflows", e);
        }
        return list;
    }

    @Override
    public void update(WorkflowDefinition def) {
        String sql = """
            UPDATE workflows
            SET name=?, description=?, definition_json=?, updated_at=?
            WHERE id=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, def.getName());
            ps.setString(2, def.getDescription());
            ps.setString(3, parser.toJson(def));
            ps.setString(4, LocalDateTime.now().toString());
            ps.setLong  (5, def.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update workflow id=" + def.getId(), e);
        }
    }

    @Override
    public void delete(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM workflows WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete workflow id=" + id, e);
        }
    }

    private WorkflowDefinition map(ResultSet rs) throws SQLException {
        String json = rs.getString("definition_json");
        WorkflowDefinition def = parser.parse(json);
        def.setId(rs.getLong("id"));
        String ca = rs.getString("created_at");
        if (ca != null) def.setCreatedAt(LocalDateTime.parse(ca));
        String ua = rs.getString("updated_at");
        if (ua != null) def.setUpdatedAt(LocalDateTime.parse(ua));
        return def;
    }
}
