package com.vulntriage.cvss;

/**
 * Computes the CVSS v3.1 Base Score from a parsed {@link CvssVector}.
 *
 * <p>Implements the official FIRST CVSS v3.1 specification formula exactly:
 * <ol>
 *   <li>ISS — Impact Sub-Score: {@code 1 - (1-C)(1-I)(1-A)}</li>
 *   <li>Impact — differs when Scope is Changed vs. Unchanged</li>
 *   <li>Exploitability — {@code 8.22 × AV × AC × PR × UI}; PR values differ when
 *       Scope is Changed</li>
 *   <li>Base Score = {@code Roundup(min(Impact + Exploitability, 10))} for Scope
 *       Unchanged; {@code Roundup(min(1.08 × (Impact + Exploitability), 10))} for
 *       Scope Changed; 0.0 when Impact ≤ 0</li>
 * </ol>
 *
 * <p>The {@code Roundup} function is implemented with integer arithmetic as specified
 * in Appendix A of the CVSS v3.1 specification to avoid floating-point drift.
 *
 * <p>This class is stateless — all methods are {@code static}.
 */
public final class CvssCalculator {

    private CvssCalculator() {}

    // ── Metric numeric weights (CVSS v3.1 spec, Table 14) ─────────────────

    private static double av(String v) {
        return switch (v) {
            case "N" -> 0.85;   // Network
            case "A" -> 0.62;   // Adjacent
            case "L" -> 0.55;   // Local
            case "P" -> 0.20;   // Physical
            default  -> throw new CvssException("Unknown AV value", v);
        };
    }

    private static double ac(String v) {
        return switch (v) {
            case "L" -> 0.77;   // Low
            case "H" -> 0.44;   // High
            default  -> throw new CvssException("Unknown AC value", v);
        };
    }

    /** PR numeric value when Scope is Unchanged. */
    private static double prUnchanged(String v) {
        return switch (v) {
            case "N" -> 0.85;
            case "L" -> 0.62;
            case "H" -> 0.27;
            default  -> throw new CvssException("Unknown PR value", v);
        };
    }

    /** PR numeric value when Scope is Changed (values are higher). */
    private static double prChanged(String v) {
        return switch (v) {
            case "N" -> 0.85;
            case "L" -> 0.68;
            case "H" -> 0.50;
            default  -> throw new CvssException("Unknown PR value", v);
        };
    }

    private static double ui(String v) {
        return switch (v) {
            case "N" -> 0.85;   // None
            case "R" -> 0.62;   // Required
            default  -> throw new CvssException("Unknown UI value", v);
        };
    }

    /** Shared weight for Confidentiality, Integrity, and Availability impact metrics. */
    private static double cia(String v) {
        return switch (v) {
            case "N" -> 0.00;
            case "L" -> 0.22;
            case "H" -> 0.56;
            default  -> throw new CvssException("Unknown C/I/A value", v);
        };
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Compute the CVSS v3.1 base score for the given vector.
     *
     * @param vec a parsed and validated CVSS v3.1 vector
     * @return base score in [0.0, 10.0], rounded to one decimal place per the spec Roundup function
     */
    public static double calculate(CvssVector vec) {
        boolean scopeChanged = vec.isScopeChanged();

        // Impact Sub-Score
        double iss = 1.0
            - (1.0 - cia(vec.c()))
            * (1.0 - cia(vec.i()))
            * (1.0 - cia(vec.a()));

        // Impact (formula differs by Scope)
        double impact;
        if (scopeChanged) {
            impact = 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15.0);
        } else {
            impact = 6.42 * iss;
        }

        if (impact <= 0.0) return 0.0;

        // Exploitability (PR weight differs by Scope)
        double pr = scopeChanged ? prChanged(vec.pr()) : prUnchanged(vec.pr());
        double exploitability = 8.22 * av(vec.av()) * ac(vec.ac()) * pr * ui(vec.ui());

        // Base Score
        double raw;
        if (scopeChanged) {
            raw = Math.min(1.08 * (impact + exploitability), 10.0);
        } else {
            raw = Math.min(impact + exploitability, 10.0);
        }

        return roundup(raw);
    }

    /**
     * Map a CVSS v3.1 base score to its qualitative severity rating.
     *
     * @param score base score in [0.0, 10.0]
     * @return "None", "Low", "Medium", "High", or "Critical"
     */
    public static String severity(double score) {
        if (score == 0.0) return "None";
        if (score <= 3.9)  return "Low";
        if (score <= 6.9)  return "Medium";
        if (score <= 8.9)  return "High";
        return "Critical";
    }

    // ── Roundup (CVSS v3.1 spec Appendix A) ───────────────────────────────

    /**
     * Rounds {@code input} up to the nearest 0.1, using the integer-math algorithm from
     * CVSS v3.1 specification Appendix A to eliminate floating-point representation drift.
     *
     * <pre>
     * intInput = int(input × 100000)
     * if (intInput mod 10000) == 0  →  intInput / 100000
     * else                          →  (floor(intInput / 10000) + 1) / 10
     * </pre>
     */
    static double roundup(double input) {
        int intInput = (int) (input * 100000);
        if ((intInput % 10000) == 0) {
            return intInput / 100000.0;
        } else {
            return (Math.floor((double) intInput / 10000.0) + 1.0) / 10.0;
        }
    }
}
