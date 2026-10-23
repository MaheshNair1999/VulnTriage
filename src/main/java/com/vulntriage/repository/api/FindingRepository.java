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

    /** Count findings for a repository without loading them into memory. */
    long countByRepositoryId(long repositoryId);

    /**
     * Count findings matching severity for a repository (or all repos when repositoryId < 0).
     * Used by Dashboard severity chart.
     */
    long countBySeverity(long repositoryId, Severity severity);

    /**
     * Keyset-paginated filtered query for large datasets.
     * Pass repositoryId=-1 for all repos. Pass afterId=0 for the first page.
     * severity/source/search: null or blank means no filter on that field.
     */
    List<Finding> findPageFiltered(long repositoryId, String severity, String source,
                                   String search, long afterId, int limit);

    /**
     * Total count matching the same filter criteria as findPageFiltered.
     * Used to show total result count alongside paginated results.
     */
    long countFiltered(long repositoryId, String severity, String source, String search);

    /**
     * Per-scanner breakdown: source → [total, errors, warnings, infos].
     * Used by Dashboard scanner cards. repositoryId < 0 = all repos.
     */
    java.util.Map<String, long[]> countByScannerBreakdown(long repositoryId);

    /**
     * Top N rule_ids by occurrence count.
     * Returns list of [rule_id, count] pairs, sorted descending.
     */
    java.util.List<String[]> findTopRuleIds(long repositoryId, int limit);

    /**
     * Top N file_paths by occurrence count.
     * Returns list of [file_path, count] pairs, sorted descending.
     */
    java.util.List<String[]> findTopFilePaths(long repositoryId, int limit);
}
