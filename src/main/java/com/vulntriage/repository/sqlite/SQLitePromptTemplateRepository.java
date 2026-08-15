package com.vulntriage.repository.sqlite;

import com.vulntriage.domain.PromptTemplate;
import com.vulntriage.repository.api.PromptTemplateRepository;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SQLitePromptTemplateRepository implements PromptTemplateRepository {

    // SQLite datetime('now') returns "yyyy-MM-dd HH:mm:ss"; Java's ISO uses 'T' separator
    private static final DateTimeFormatter SQLITE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Connection conn;

    public SQLitePromptTemplateRepository() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    @Override
    public void save(PromptTemplate t) {
        String sql = """
            INSERT INTO prompt_templates (name, version, description, template, built_in, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, t.getName());
            ps.setString(2, t.getVersion());
            ps.setString(3, t.getDescription());
            ps.setString(4, t.getTemplate());
            ps.setInt   (5, t.isBuiltIn() ? 1 : 0);
            ps.setString(6, LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) t.setId(keys.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save prompt template", e);
        }
    }

    @Override
    public void update(PromptTemplate t) {
        String sql = """
            UPDATE prompt_templates SET name=?, version=?, description=?, template=?, built_in=?
            WHERE id=?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.getName());
            ps.setString(2, t.getVersion());
            ps.setString(3, t.getDescription());
            ps.setString(4, t.getTemplate());
            ps.setInt   (5, t.isBuiltIn() ? 1 : 0);
            ps.setLong  (6, t.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update prompt template", e);
        }
    }

    @Override
    public void deleteById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM prompt_templates WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete prompt template id=" + id, e);
        }
    }

    @Override
    public Optional<PromptTemplate> findById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM prompt_templates WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find prompt template id=" + id, e);
        }
    }

    @Override
    public Optional<PromptTemplate> findByVersion(String version) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM prompt_templates WHERE version=? LIMIT 1")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find prompt template version=" + version, e);
        }
    }

    @Override
    public List<PromptTemplate> findAll() {
        List<PromptTemplate> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM prompt_templates ORDER BY id ASC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list prompt templates", e);
        }
        return list;
    }

    private PromptTemplate map(ResultSet rs) throws SQLException {
        PromptTemplate t = new PromptTemplate();
        t.setId         (rs.getLong  ("id"));
        t.setName       (rs.getString("name"));
        t.setVersion    (rs.getString("version"));
        t.setDescription(rs.getString("description"));
        t.setTemplate   (rs.getString("template"));
        t.setBuiltIn    (rs.getInt   ("built_in") == 1);
        String ca = rs.getString("created_at");
        if (ca != null) {
            try {
                t.setCreatedAt(LocalDateTime.parse(ca));            // ISO: "2026-08-13T05:33:24"
            } catch (DateTimeParseException e) {
                try {
                    t.setCreatedAt(LocalDateTime.parse(ca, SQLITE_FMT)); // SQLite: "2026-08-13 05:33:24"
                } catch (DateTimeParseException ignored) {}
            }
        }
        return t;
    }
}
