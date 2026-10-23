package com.vulntriage.repository;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.FindingRepository;

import java.util.List;
import java.util.Optional;

/**
 * Decorator pattern — wraps a {@link FindingRepository} and passes all calls
 * through to the delegate. The previous list-based cache was removed because
 * caching entire finding lists at 1M scale causes OOM. All callers that need
 * counts should use {@link #countByRepositoryId} instead of loading the list.
 */
public class CachingFindingRepository implements FindingRepository {

    private final FindingRepository delegate;

    public CachingFindingRepository(FindingRepository delegate) {
        this.delegate = delegate;
    }

    // ── Passthrough reads/writes ───────────────────────────────────────────

    @Override
    public List<Finding> findByRepositoryId(long repositoryId) {
        return delegate.findByRepositoryId(repositoryId);
    }

    @Override
    public void save(Finding finding) {
        delegate.save(finding);
    }

    @Override
    public void deleteByScanRunId(long scanRunId) {
        delegate.deleteByScanRunId(scanRunId);
    }

    @Override
    public void deleteById(long id) {
        delegate.deleteById(id);
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

    @Override
    public long countByRepositoryId(long repositoryId) {
        return delegate.countByRepositoryId(repositoryId);
    }

    @Override
    public long countBySeverity(long repositoryId, Severity severity) {
        return delegate.countBySeverity(repositoryId, severity);
    }

    @Override
    public List<Finding> findPageFiltered(long repositoryId, String severity, String source,
                                          String search, long afterId, int limit) {
        return delegate.findPageFiltered(repositoryId, severity, source, search, afterId, limit);
    }

    @Override
    public long countFiltered(long repositoryId, String severity, String source, String search) {
        return delegate.countFiltered(repositoryId, severity, source, search);
    }

    @Override
    public java.util.Map<String, long[]> countByScannerBreakdown(long repositoryId) {
        return delegate.countByScannerBreakdown(repositoryId);
    }

    @Override
    public java.util.List<String[]> findTopRuleIds(long repositoryId, int limit) {
        return delegate.findTopRuleIds(repositoryId, limit);
    }

    @Override
    public java.util.List<String[]> findTopFilePaths(long repositoryId, int limit) {
        return delegate.findTopFilePaths(repositoryId, limit);
    }
}
