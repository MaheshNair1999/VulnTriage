package com.vulntriage.sampling;

import com.vulntriage.domain.Finding;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Stratified random sampling algorithm.
 *
 * Groups findings by (category, severity) strata, then draws a proportional
 * quota from each stratum. This guarantees rare vulnerability categories are
 * represented in the sample — pure random sampling would often miss them.
 *
 * Example: 358 findings, 10 are SQL injection (2.8%), target sample = 49.
 * Stratified: at least 1 SQL injection finding is guaranteed in the sample.
 * Pure random: 36% chance of zero SQL injection findings in sample.
 *
 * Algorithm:
 *   1. Group findings by stratum key = category + "|" + severity
 *   2. Calculate each stratum's quota = round(stratum_size / total * target)
 *   3. Shuffle each stratum and take the quota
 *   4. If total < target (rounding loss), top up from remainder pool
 *   5. Return the combined sample, shuffled
 */
public class StratifiedSampler {

    private final Random random;

    public StratifiedSampler() {
        this.random = new Random();
    }

    /** Constructor for testing with a fixed seed for reproducibility. */
    public StratifiedSampler(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Draw a stratified sample from the given findings.
     *
     * @param findings   full list to sample from
     * @param targetSize desired sample size
     * @return sample of at most targetSize findings (may be smaller if findings < targetSize)
     */
    public List<Finding> sample(List<Finding> findings, int targetSize) {
        if (findings == null || findings.isEmpty()) return Collections.emptyList();
        if (targetSize <= 0)                        return Collections.emptyList();
        if (findings.size() <= targetSize)          return new ArrayList<>(findings);

        // Step 1: group by stratum
        Map<String, List<Finding>> strata = findings.stream()
            .collect(Collectors.groupingBy(this::stratumKey));

        int total = findings.size();
        List<Finding> sample = new ArrayList<>();

        // Step 2 & 3: proportional quota per stratum
        for (Map.Entry<String, List<Finding>> entry : strata.entrySet()) {
            List<Finding> stratum = new ArrayList<>(entry.getValue());
            Collections.shuffle(stratum, random);

            double proportion = (double) stratum.size() / total;
            int quota = (int) Math.round(proportion * targetSize);
            quota = Math.max(1, Math.min(quota, stratum.size())); // at least 1, at most stratum size

            sample.addAll(stratum.subList(0, quota));
        }

        // Step 4: top up or trim to hit targetSize exactly
        if (sample.size() < targetSize) {
            // Collect findings not yet in sample
            Set<Long> selectedIds = sample.stream()
                .map(Finding::getId)
                .collect(Collectors.toSet());

            List<Finding> remainder = findings.stream()
                .filter(f -> !selectedIds.contains(f.getId()))
                .collect(Collectors.toList());
            Collections.shuffle(remainder, random);

            int needed = targetSize - sample.size();
            sample.addAll(remainder.subList(0, Math.min(needed, remainder.size())));

        } else if (sample.size() > targetSize) {
            // Trim (rounding can overshoot by a few)
            Collections.shuffle(sample, random);
            sample = sample.subList(0, targetSize);
        }

        Collections.shuffle(sample, random);
        return new ArrayList<>(sample);
    }

    private String stratumKey(Finding f) {
        String category = f.getCategory() != null ? f.getCategory() : "unknown";
        String severity = f.getSeverity() != null ? f.getSeverity().name() : "INFO";
        return category + "|" + severity;
    }
}
