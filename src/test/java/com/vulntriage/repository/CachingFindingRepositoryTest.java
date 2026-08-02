package com.vulntriage.repository;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.FindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CachingFindingRepository}.
 *
 * Uses a hand-written counting stub rather than a real database.
 * Tests: cache hit (second call skips delegate), invalidation on save and delete.
 */
class CachingFindingRepositoryTest {

    private CountingDelegate delegate;
    private CachingFindingRepository cache;

    @BeforeEach
    void setUp() {
        delegate = new CountingDelegate();
        cache    = new CachingFindingRepository(delegate);
    }

    // ── Cache hit ──────────────────────────────────────────────────────────

    @Test
    void findByRepositoryId_firstCall_delegateInvokedOnce() {
        cache.findByRepositoryId(1L);
        assertEquals(1, delegate.findByRepositoryIdCount);
    }

    @Test
    void findByRepositoryId_secondCallSameId_delegateNotInvokedAgain() {
        cache.findByRepositoryId(1L);
        cache.findByRepositoryId(1L);
        assertEquals(1, delegate.findByRepositoryIdCount, "Cache should serve second call");
    }

    @Test
    void findByRepositoryId_differentIds_eachIdQueriedOnce() {
        cache.findByRepositoryId(1L);
        cache.findByRepositoryId(2L);
        cache.findByRepositoryId(1L);
        cache.findByRepositoryId(2L);
        assertEquals(2, delegate.findByRepositoryIdCount, "One delegate call per distinct repo id");
    }

    @Test
    void findByRepositoryId_returnsDelegateResult() {
        Finding stub = new Finding();
        stub.setId(99L);
        delegate.stubResult = List.of(stub);

        List<Finding> result = cache.findByRepositoryId(7L);
        assertEquals(1, result.size());
        assertEquals(99L, result.get(0).getId());
    }

    // ── Invalidation on write ──────────────────────────────────────────────

    @Test
    void save_invalidatesCache() {
        cache.findByRepositoryId(1L);            // primes cache
        cache.save(new Finding());               // should clear cache
        cache.findByRepositoryId(1L);            // must go to delegate again
        assertEquals(2, delegate.findByRepositoryIdCount, "Cache should be cleared after save");
    }

    @Test
    void deleteByScanRunId_invalidatesCache() {
        cache.findByRepositoryId(1L);
        cache.deleteByScanRunId(42L);
        cache.findByRepositoryId(1L);
        assertEquals(2, delegate.findByRepositoryIdCount);
    }

    @Test
    void deleteById_invalidatesCache() {
        cache.findByRepositoryId(1L);
        cache.deleteById(5L);
        cache.findByRepositoryId(1L);
        assertEquals(2, delegate.findByRepositoryIdCount);
    }

    // ── Passthrough methods delegate without caching ───────────────────────

    @Test
    void findById_alwaysDelegates() {
        cache.findById(1L);
        cache.findById(1L);
        assertEquals(2, delegate.findByIdCount);
    }

    @Test
    void existsByFingerprint_alwaysDelegates() {
        cache.existsByFingerprint("fp");
        cache.existsByFingerprint("fp");
        assertEquals(2, delegate.existsByFingerprintCount);
    }

    // ── Counting stub ──────────────────────────────────────────────────────

    static class CountingDelegate implements FindingRepository {

        int findByRepositoryIdCount  = 0;
        int findByIdCount            = 0;
        int existsByFingerprintCount = 0;
        List<Finding> stubResult     = List.of();

        @Override
        public List<Finding> findByRepositoryId(long repositoryId) {
            findByRepositoryIdCount++;
            return stubResult;
        }

        @Override
        public Optional<Finding> findById(long id) {
            findByIdCount++;
            return Optional.empty();
        }

        @Override
        public boolean existsByFingerprint(String fingerprint) {
            existsByFingerprintCount++;
            return false;
        }

        @Override public void save(Finding f)                     {}
        @Override public List<Finding> findByScanRunId(long id)  { return List.of(); }
        @Override public List<Finding> findUnreviewed()           { return List.of(); }
        @Override public List<Finding> findBySeverity(Severity s) { return List.of(); }
        @Override public List<Finding> findByCategory(String c)  { return List.of(); }
        @Override public long countAll()                          { return 0; }
        @Override public long countByVerdict(Verdict v)          { return 0; }
        @Override public long countUnreviewed()                   { return 0; }
        @Override public void deleteByScanRunId(long id)         {}
        @Override public void deleteById(long id)                {}
    }
}
