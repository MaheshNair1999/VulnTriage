package com.vulntriage.repository.sqlite;

import com.vulntriage.domain.ManualReview;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.ManualReviewRepository;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SQLiteManualReviewRepository implements ManualReviewRepository {

    private final Connection conn;

    public SQLiteManualReviewRepository() {
        this.conn = SQLiteConnection.getInstance().getConnection();
    }

    @Override
    public void saveOrUpdate(ManualReview review) {
        // UPSERT — insert or replace if finding_id already has a review
        String sql = """
            INSERT INTO manual_reviews (finding_id, verdict, notes, reviewed_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(finding_id) DO UPDATE SET
                verdict     = excluded.verdict,
                notes       = excluded.notes,
                reviewed_at = excluded.reviewed_at
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong  (1, review.getFindingId());
            ps.setString(2, review.getVerdict().name());
            ps.setString(3, review.getNotes());
            ps.setString(4, LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) review.setId(keys.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save manual review", e);
        }
    }

    @Override
    public Optional<ManualReview> findByFindingId(long findingId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM manual_reviews WHERE finding_id = ?")) {
            ps.setLong(1, findingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find review for finding id=" + findingId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<ManualReview> findAll() {
        List<ManualReview> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM manual_reviews");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list reviews", e);
        }
        return list;
    }

    @Override
    public List<ManualReview> findByVerdict(Verdict verdict) {
        List<ManualReview> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM manual_reviews WHERE verdict = ?")) {
            ps.setString(1, verdict.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find reviews by verdict", e);
        }
        return list;
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
            throw new RuntimeException("Failed to count reviews by verdict", e);
        }
    }

    @Override
    public long countAll() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM manual_reviews");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count reviews", e);
        }
    }

    @Override
    public long countForFindingIds(List<Long> findingIds) {
        if (findingIds.isEmpty()) return 0;
        String placeholders = findingIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = "SELECT COUNT(*) FROM manual_reviews WHERE finding_id IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < findingIds.size(); i++) ps.setLong(i + 1, findingIds.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count reviews for finding ids", e);
        }
    }

    @Override
    public void deleteByFindingId(long findingId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM manual_reviews WHERE finding_id = ?")) {
            ps.setLong(1, findingId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete review for finding id=" + findingId, e);
        }
    }

    private ManualReview map(ResultSet rs) throws SQLException {
        ManualReview mr = new ManualReview();
        mr.setId       (rs.getLong  ("id"));
        mr.setFindingId(rs.getLong  ("finding_id"));
        mr.setVerdict  (Verdict.valueOf(rs.getString("verdict")));
        mr.setNotes    (rs.getString("notes"));
        String ra = rs.getString("reviewed_at");
        if (ra != null) mr.setReviewedAt(LocalDateTime.parse(ra));
        return mr;
    }
}
