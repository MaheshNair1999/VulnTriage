package com.vulntriage.repository.sqlite;

import com.vulntriage.domain.Repository;
import com.vulntriage.repository.api.RepositoryRepo;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SQLiteRepositoryRepo implements RepositoryRepo {

    private final Connection conn;

    public SQLiteRepositoryRepo() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    @Override
    public void save(Repository r) {
        String sql = "INSERT INTO repositories (name, local_path, url, version, created_at) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.getName());
            ps.setString(2, r.getLocalPath());
            ps.setString(3, r.getUrl());
            ps.setString(4, r.getVersion());
            ps.setString(5, LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) r.setId(keys.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save repository", e);
        }
    }

    @Override
    public Optional<Repository> findById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM repositories WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find repository id=" + id, e);
        }
        return Optional.empty();
    }

    @Override
    public List<Repository> findAll() {
        List<Repository> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM repositories WHERE hidden = 0 OR hidden IS NULL ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list repositories", e);
        }
        return list;
    }

    @Override
    public void update(Repository r) {
        String sql = "UPDATE repositories SET name=?, local_path=?, url=?, version=?, last_scanned=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.getName());
            ps.setString(2, r.getLocalPath());
            ps.setString(3, r.getUrl());
            ps.setString(4, r.getVersion());
            ps.setString(5, r.getLastScanned() != null ? r.getLastScanned().toString() : null);
            ps.setLong  (6, r.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update repository id=" + r.getId(), e);
        }
    }

    @Override
    public void delete(long id) {
        // Soft-delete: mark hidden so findings keep their FK reference
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE repositories SET hidden = 1 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to hide repository id=" + id, e);
        }
    }

    @Override
    public long countAll() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM repositories");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count repositories", e);
        }
    }

    private Repository map(ResultSet rs) throws SQLException {
        Repository r = new Repository();
        r.setId       (rs.getLong  ("id"));
        r.setName     (rs.getString("name"));
        r.setLocalPath(rs.getString("local_path"));
        r.setUrl      (rs.getString("url"));
        r.setVersion  (rs.getString("version"));
        String ca = rs.getString("created_at");
        if (ca != null) r.setCreatedAt(LocalDateTime.parse(ca));
        String ls = rs.getString("last_scanned");
        if (ls != null) r.setLastScanned(LocalDateTime.parse(ls));
        return r;
    }
}
