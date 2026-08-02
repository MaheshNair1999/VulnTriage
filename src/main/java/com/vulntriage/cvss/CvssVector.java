package com.vulntriage.cvss;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Parses and holds the eight mandatory base metrics of a CVSS v3.1 vector string.
 *
 * <p>Expected format: {@code CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H}
 *
 * <p>Validation rules enforced by {@link #parse(String)}:
 * <ul>
 *   <li>Prefix must be exactly {@code CVSS:3.1/}</li>
 *   <li>All eight base metrics (AV, AC, PR, UI, S, C, I, A) must be present</li>
 *   <li>Unknown metric abbreviations throw {@link CvssException} with the offending key</li>
 *   <li>Unknown metric values throw {@link CvssException} with the offending value</li>
 * </ul>
 *
 * <p>Temporal and environmental metrics are silently ignored when present, as this
 * implementation only computes the base score.
 */
public class CvssVector {

    /** The eight mandatory CVSS v3.1 base metrics. */
    public enum Metric { AV, AC, PR, UI, S, C, I, A }

    // Mapping from string abbreviation to enum constant
    private static final Map<String, Metric> METRIC_MAP = Map.of(
        "AV", Metric.AV,
        "AC", Metric.AC,
        "PR", Metric.PR,
        "UI", Metric.UI,
        "S",  Metric.S,
        "C",  Metric.C,
        "I",  Metric.I,
        "A",  Metric.A
    );

    // Valid values per metric
    private static final Map<String, Set<String>> VALID_VALUES = Map.of(
        "AV", Set.of("N", "A", "L", "P"),
        "AC", Set.of("L", "H"),
        "PR", Set.of("N", "L", "H"),
        "UI", Set.of("N", "R"),
        "S",  Set.of("U", "C"),
        "C",  Set.of("N", "L", "H"),
        "I",  Set.of("N", "L", "H"),
        "A",  Set.of("N", "L", "H")
    );

    private final Map<Metric, String> values;

    private CvssVector(Map<Metric, String> values) {
        this.values = values;
    }

    /**
     * Parse a CVSS v3.1 base vector string.
     *
     * @param vector the vector string, e.g. {@code CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H}
     * @return a validated {@code CvssVector}
     * @throws CvssException if the string is null, blank, has a wrong prefix, contains an unknown
     *                       metric or value, or is missing a mandatory base metric
     */
    public static CvssVector parse(String vector) {
        if (vector == null || vector.isBlank()) {
            throw new CvssException("CVSS vector must not be null or blank", "(null)");
        }

        if (!vector.startsWith("CVSS:3.1/")) {
            String found = vector.contains("/") ? vector.substring(0, vector.indexOf('/')) : vector;
            throw new CvssException("Vector must start with 'CVSS:3.1/'", found);
        }

        String body = vector.substring("CVSS:3.1/".length());
        String[] parts = body.split("/");

        Map<Metric, String> values = new EnumMap<>(Metric.class);

        for (String part : parts) {
            int colon = part.indexOf(':');
            if (colon < 1 || colon == part.length() - 1) {
                throw new CvssException("Malformed metric token (expected KEY:VALUE)", part);
            }
            String key   = part.substring(0, colon);
            String value = part.substring(colon + 1);

            Metric metric = METRIC_MAP.get(key);
            if (metric == null) {
                // Unknown metric abbreviation (could be temporal/env — reject unknown entirely)
                throw new CvssException("Unknown metric abbreviation", key);
            }

            Set<String> allowed = VALID_VALUES.get(key);
            if (!allowed.contains(value)) {
                throw new CvssException("Invalid value for metric " + key, value);
            }

            values.put(metric, value);
        }

        // Verify all eight mandatory base metrics are present
        for (Metric m : Metric.values()) {
            if (!values.containsKey(m)) {
                throw new CvssException("Missing mandatory base metric", m.name());
            }
        }

        return new CvssVector(values);
    }

    /** Raw string value of a metric (e.g. {@code "N"}, {@code "H"}). */
    public String get(Metric metric) { return values.get(metric); }

    // Convenience accessors used by CvssCalculator
    public String av() { return values.get(Metric.AV); }
    public String ac() { return values.get(Metric.AC); }
    public String pr() { return values.get(Metric.PR); }
    public String ui() { return values.get(Metric.UI); }
    public String s()  { return values.get(Metric.S);  }
    public String c()  { return values.get(Metric.C);  }
    public String i()  { return values.get(Metric.I);  }
    public String a()  { return values.get(Metric.A);  }

    /** {@code true} when the Scope metric is Changed (S:C). */
    public boolean isScopeChanged() { return "C".equals(values.get(Metric.S)); }

    @Override
    public String toString() {
        return "CVSS:3.1/AV:" + av() + "/AC:" + ac() + "/PR:" + pr()
             + "/UI:" + ui() + "/S:" + s() + "/C:" + c() + "/I:" + i() + "/A:" + a();
    }
}
