package com.vulntriage.evaluation;

import com.vulntriage.domain.Finding;
import com.vulntriage.repository.api.FindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Trend Delta Algorithm — computes the change in vulnerability findings
 * between two consecutive scan runs on the same repository.
 *
 * Algorithm:
 *   Let P = set of fingerprints in the previous scan run
 *   Let C = set of fingerprints in the current scan run
 *
 *   new        = C \ P  (in current, not in previous — newly introduced)
 *   resolved   = P \ C  (in previous, not in current — fixed or disappeared)
 *   persistent = C ∩ P  (in both — present across multiple runs)
 *
 * The fingerprint is used as the finding identity because it is a
 * deterministic hash of (repositoryId, filePath, lineNumber, ruleId).
 * Two findings with the same fingerprint are the same issue.
 *
 * Complexity: O(n + m) where n = |P|, m = |C|
 * — both sets are built in linear time; set operations are O(1) average.
 */
public class TrendDeltaCalculator {

    private static final Logger log = LoggerFactory.getLogger(TrendDeltaCalculator.class);

    private final FindingRepository findingRepo;

    public TrendDeltaCalculator(FindingRepository findingRepo) {
        this.findingRepo = findingRepo;
    }

    /**
     * Compute the trend delta between two scan runs.
     *
     * @param previousScanRunId ID of the earlier scan run
     * @param currentScanRunId  ID of the more recent scan run
     * @return TrendDelta containing new, resolved, and persistent finding sets
     */
    public TrendDelta compute(long previousScanRunId, long currentScanRunId) {
        Set<String> previous = fingerprintsForRun(previousScanRunId);
        Set<String> current  = fingerprintsForRun(currentScanRunId);

        // New = current minus previous
        Set<String> newFindings = new HashSet<>(current);
        newFindings.removeAll(previous);

        // Resolved = previous minus current
        Set<String> resolved = new HashSet<>(previous);
        resolved.removeAll(current);

        // Persistent = intersection (current ∩ previous)
        Set<String> persistent = new HashSet<>(current);
        persistent.retainAll(previous);

        TrendDelta delta = new TrendDelta(newFindings, resolved, persistent);

        log.info("TrendDelta: runId {} → {}: {}",
            previousScanRunId, currentScanRunId, delta);

        return delta;
    }

    /**
     * Compute the trend delta between two pre-built fingerprint sets.
     * Used when the caller already has the fingerprints in memory
     * (e.g. from in-memory stubs in tests).
     */
    public TrendDelta computeFromSets(Set<String> previousFingerprints,
                                      Set<String> currentFingerprints) {
        Set<String> newFindings = new HashSet<>(currentFingerprints);
        newFindings.removeAll(previousFingerprints);

        Set<String> resolved = new HashSet<>(previousFingerprints);
        resolved.removeAll(currentFingerprints);

        Set<String> persistent = new HashSet<>(currentFingerprints);
        persistent.retainAll(previousFingerprints);

        return new TrendDelta(newFindings, resolved, persistent);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Set<String> fingerprintsForRun(long scanRunId) {
        return findingRepo.findByScanRunId(scanRunId).stream()
            .map(Finding::getFingerprint)
            .filter(fp -> fp != null)
            .collect(Collectors.toCollection(HashSet::new));
    }
}
