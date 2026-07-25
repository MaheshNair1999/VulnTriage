package com.vulntriage.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Trend Delta Algorithm.
 *
 * Uses computeFromSets() which takes pre-built fingerprint sets
 * directly — no DB or repository needed.
 */
class TrendDeltaCalculatorTest {

    private TrendDeltaCalculator calculator;

    @BeforeEach
    void setup() {
        // Pass null for findingRepo — tests use computeFromSets() which doesn't need it
        calculator = new TrendDeltaCalculator(null);
    }

    @Test
    void compute_allNew_whenPreviousIsEmpty() {
        Set<String> previous = Set.of();
        Set<String> current  = Set.of("fp-1", "fp-2", "fp-3");

        TrendDelta delta = calculator.computeFromSets(previous, current);

        assertEquals(3, delta.newCount());
        assertEquals(0, delta.resolvedCount());
        assertEquals(0, delta.persistentCount());
    }

    @Test
    void compute_allResolved_whenCurrentIsEmpty() {
        Set<String> previous = Set.of("fp-1", "fp-2");
        Set<String> current  = Set.of();

        TrendDelta delta = calculator.computeFromSets(previous, current);

        assertEquals(0, delta.newCount());
        assertEquals(2, delta.resolvedCount());
        assertEquals(0, delta.persistentCount());
    }

    @Test
    void compute_allPersistent_whenSetsAreIdentical() {
        Set<String> fingerprints = Set.of("fp-1", "fp-2", "fp-3");

        TrendDelta delta = calculator.computeFromSets(fingerprints, fingerprints);

        assertEquals(0, delta.newCount());
        assertEquals(0, delta.resolvedCount());
        assertEquals(3, delta.persistentCount());
    }

    @Test
    void compute_mixedSets_correctlyPartitions() {
        Set<String> previous = Set.of("fp-1", "fp-2", "fp-3");
        Set<String> current  = Set.of("fp-2", "fp-3", "fp-4");
        // new       = {fp-4}
        // resolved  = {fp-1}
        // persistent = {fp-2, fp-3}

        TrendDelta delta = calculator.computeFromSets(previous, current);

        assertEquals(1, delta.newCount());
        assertEquals(1, delta.resolvedCount());
        assertEquals(2, delta.persistentCount());
        assertTrue(delta.getNewFingerprints().contains("fp-4"));
        assertTrue(delta.getResolvedFingerprints().contains("fp-1"));
        assertTrue(delta.getPersistentFingerprints().contains("fp-2"));
        assertTrue(delta.getPersistentFingerprints().contains("fp-3"));
    }

    @Test
    void postureDelta_positive_whenMoreNewThanResolved() {
        Set<String> previous = Set.of("fp-1");
        Set<String> current  = Set.of("fp-2", "fp-3", "fp-4"); // 3 new, 1 resolved

        TrendDelta delta = calculator.computeFromSets(previous, current);

        assertTrue(delta.postureDelta() > 0, "More new than resolved = worse posture");
    }

    @Test
    void postureDelta_negative_whenMoreResolvedThanNew() {
        Set<String> previous = Set.of("fp-1", "fp-2", "fp-3");
        Set<String> current  = Set.of("fp-1", "fp-4"); // 1 new, 2 resolved

        TrendDelta delta = calculator.computeFromSets(previous, current);

        assertTrue(delta.postureDelta() < 0, "More resolved than new = better posture");
    }

    @Test
    void postureDelta_zero_whenNewEqualsResolved() {
        Set<String> previous = Set.of("fp-1", "fp-2");
        Set<String> current  = Set.of("fp-2", "fp-3"); // 1 new, 1 resolved

        TrendDelta delta = calculator.computeFromSets(previous, current);

        assertEquals(0, delta.postureDelta());
    }

    @Test
    void sets_areDisjoint() {
        Set<String> previous = Set.of("fp-1", "fp-2", "fp-3");
        Set<String> current  = Set.of("fp-2", "fp-3", "fp-4");

        TrendDelta delta = calculator.computeFromSets(previous, current);

        // The three sets must be completely disjoint
        Set<String> newSet        = delta.getNewFingerprints();
        Set<String> resolvedSet   = delta.getResolvedFingerprints();
        Set<String> persistentSet = delta.getPersistentFingerprints();

        assertTrue(newSet.stream().noneMatch(resolvedSet::contains));
        assertTrue(newSet.stream().noneMatch(persistentSet::contains));
        assertTrue(resolvedSet.stream().noneMatch(persistentSet::contains));
    }
}
