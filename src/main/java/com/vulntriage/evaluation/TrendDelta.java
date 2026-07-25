package com.vulntriage.evaluation;

import java.util.Set;

/**
 * Result of a trend delta computation between two scan runs.
 *
 * Contains three disjoint sets of fingerprints:
 *   newFingerprints        — appeared in the current run, not in the previous
 *   resolvedFingerprints   — appeared in the previous run, not in the current
 *   persistentFingerprints — appeared in both runs
 *
 * Interpretation:
 *   - New findings may indicate newly introduced vulnerabilities
 *   - Resolved findings may indicate fixes (or rule changes)
 *   - Persistent findings are the most important: they have been present across runs
 */
public class TrendDelta {

    private final Set<String> newFingerprints;
    private final Set<String> resolvedFingerprints;
    private final Set<String> persistentFingerprints;

    public TrendDelta(Set<String> newFingerprints,
                      Set<String> resolvedFingerprints,
                      Set<String> persistentFingerprints) {
        this.newFingerprints        = newFingerprints;
        this.resolvedFingerprints   = resolvedFingerprints;
        this.persistentFingerprints = persistentFingerprints;
    }

    public Set<String> getNewFingerprints()         { return newFingerprints; }
    public Set<String> getResolvedFingerprints()    { return resolvedFingerprints; }
    public Set<String> getPersistentFingerprints()  { return persistentFingerprints; }

    public int newCount()        { return newFingerprints.size(); }
    public int resolvedCount()   { return resolvedFingerprints.size(); }
    public int persistentCount() { return persistentFingerprints.size(); }

    /**
     * The security posture delta: negative means things got better
     * (more resolved than new), positive means worse.
     */
    public int postureDelta() {
        return newCount() - resolvedCount();
    }

    @Override
    public String toString() {
        return String.format(
            "TrendDelta{new=%d, resolved=%d, persistent=%d, posture=%+d}",
            newCount(), resolvedCount(), persistentCount(), postureDelta());
    }
}
