package com.vulntriage.repository;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.FindingRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decorator pattern — wraps a {@link FindingRepository} to cache the results of
 * {@link #findByRepositoryId(long)}, which is the hottest read path in the UI:
 * every screen repaint (Findings, Review, Dashboard) calls it for each repository.
 *
 * <p>Cache semantics:
 * <ul>
 *   <li>{@link #findByRepositoryId} results are stored per {@code repositoryId} in a
 *       {@link HashMap}. Subsequent calls with the same id return the cached list
 *       without hitting the database.</li>
 *   <li>Any write operation ({@link #save}, {@link #deleteByScanRunId},
 *       {@link #deleteById}) clears the entire cache, ensuring reads always reflect
 *       committed state.</li>
 * </ul>
 *
 * <p>All other {@link FindingRepository} methods delegate directly to the wrapped
 * instance without caching, as they are either write operations or infrequently used
 * reads where stale data would be wrong (e.g. {@link #existsByFingerprint}).
 */
public class CachingFindingRepository implements FindingRepository {

    private final FindingRepository delegate;
    private final Map<Long, List<Finding>> cache = new HashMap<>();

    /**
     * @param delegate the underlying repository to decorate
     */
    public CachingFindingRepository(FindingRepository delegate) {
        this.delegate = delegate;
    }

    // ── Cached read ────────────────────────────────────────────────────────

    @Override
    public List<Finding> findByRepositoryId(long repositoryId) {
        return cache.computeIfAbsent(repositoryId, delegate::findByRepositoryId);
    }

    // ── Write operations — invalidate cache ────────────────────────────────

    @Override
    public void save(Finding finding) {
        delegate.save(finding);
        cache.clear();
    }

    @Override
    public void deleteByScanRunId(long scanRunId) {
        delegate.deleteByScanRunId(scanRunId);
        cache.clear();
    }

    @Override
    public void deleteById(long id) {
        delegate.deleteById(id);
        cache.clear();
    }

    // ── Passthrough reads ──────────────────────────────────────────────────

    @Override
    public Optional<Finding> findById(long id) {
        return delegate.findById(id);
    }

    @Override
    public List<Finding> findByScanRunId(long scanRunId) {
        return delegate.findByScanRunId(scanRunId);
    }

    @Override
    public List<Finding> findUnreviewed() {
        return delegate.findUnreviewed();
    }

    @Override
    public List<Finding> findBySeverity(Severity severity) {
        return delegate.findBySeverity(severity);
    }

    @Override
    public List<Finding> findByCategory(String category) {
        return delegate.findByCategory(category);
    }

    @Override
    public boolean existsByFingerprint(String fingerprint) {
        return delegate.existsByFingerprint(fingerprint);
    }

    @Override
    public long countAll() {
        return delegate.countAll();
    }

    @Override
    public long countByVerdict(Verdict verdict) {
        return delegate.countByVerdict(verdict);
    }

    @Override
    public long countUnreviewed() {
        return delegate.countUnreviewed();
    }
}
