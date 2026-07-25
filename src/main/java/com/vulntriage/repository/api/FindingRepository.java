package com.vulntriage.repository.api;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Finding persistence.
 *
 * All database access for findings goes through this interface.
 * The SQLite implementation can be swapped for a PostgreSQL implementation
 * without changing any business logic (Repository pattern).
 */
public interface FindingRepository {

    /** Persist a new finding. Sets the generated ID on the object. */
    void save(Finding finding);

    /** Retrieve a finding by its primary key. */
    Optional<Finding> findById(long id);

    /** All findings for a given repository. */
    List<Finding> findByRepositoryId(long repositoryId);

    /** All findings for a given scan run. */
    List<Finding> findByScanRunId(long scanRunId);

    /** Findings that have not yet been manually reviewed. */
    List<Finding> findUnreviewed();

    /** Findings filtered by severity. */
    List<Finding> findBySeverity(Severity severity);

    /** Findings filtered by vulnerability category (e.g. "xss"). */
    List<Finding> findByCategory(String category);

    /** Check if a finding with this fingerprint already exists (for dedup). */
    boolean existsByFingerprint(String fingerprint);

    /** Total number of findings in the database. */
    long countAll();

    /** Number of findings that have a manual review with the given verdict. */
    long countByVerdict(com.vulntriage.domain.enums.Verdict verdict);

    /** Number of findings that have not yet been manually reviewed. */
    long countUnreviewed();

    /** Delete all findings for a scan run (used when rescanning). */
    void deleteByScanRunId(long scanRunId);

    /** Delete a single finding by its primary key. */
    void deleteById(long id);
}
