package com.vulntriage.cvss;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CvssCalculator}.
 *
 * All expected scores are taken from official NVD entries and the CVSS v3.1 specification.
 * Tests cover: Scope Unchanged (S:U), Scope Changed (S:C), zero-impact (None),
 * all four Attack Vector values, and the Roundup function.
 */
class CvssCalculatorTest {

    private static final double DELTA = 0.05; // allow ±0.05 for floating-point rounding

    private static double score(String vector) {
        return CvssCalculator.calculate(CvssVector.parse(vector));
    }

    // ── Official NVD / spec fixture vectors ───────────────────────────────

    @Test
    void score_log4Shell_critical_10_0() {
        // CVE-2021-44228 (Log4Shell) — NVD: 10.0 Critical
        assertEquals(10.0, score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"), DELTA);
    }

    @Test
    void score_heartbleed_high_7_5() {
        // CVE-2014-0160 (Heartbleed) — NVD: 7.5 High
        assertEquals(7.5, score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N"), DELTA);
    }

    @Test
    void score_struts_critical_9_8() {
        // CVE-2017-5638 / typical high-severity RCE — NVD: 9.8 Critical
        assertEquals(9.8, score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"), DELTA);
    }

    @Test
    void score_xssReflected_medium_6_1() {
        // Common reflected-XSS vector (S:C, low C/I, no A) — NVD: 6.1 Medium
        assertEquals(6.1, score("CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N"), DELTA);
    }

    @Test
    void score_localInfoDisclosure_medium_5_5() {
        // Local low-priv info-disclosure — NVD: 5.5 Medium
        assertEquals(5.5, score("CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N"), DELTA);
    }

    @Test
    void score_networkAvailabilityOnly_high_7_5() {
        // DoS only — NVD: 7.5 High (symmetric with Heartbleed)
        assertEquals(7.5, score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"), DELTA);
    }

    @Test
    void score_physicalFull_medium_6_8() {
        // Physical access needed, all impacts High — NVD: 6.8 Medium
        assertEquals(6.8, score("CVSS:3.1/AV:P/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"), DELTA);
    }

    @Test
    void score_highPrivScopeChanged_critical_9_1() {
        // High privileges, S:C, full impact — NVD: 9.1 Critical
        assertEquals(9.1, score("CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:C/C:H/I:H/A:H"), DELTA);
    }

    @Test
    void score_lowPrivScopeChanged_critical_9_9() {
        // Low privileges, S:C, full impact — NVD: 9.9 Critical
        assertEquals(9.9, score("CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:C/C:H/I:H/A:H"), DELTA);
    }

    @Test
    void score_allNoneImpacts_zeroScore() {
        // C:N/I:N/A:N → impact = 0 → base score = 0.0
        assertEquals(0.0, score("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"), DELTA);
    }

    @Test
    void score_highComplexityLocalHighPriv_medium_6_7() {
        // Local, high-priv, low complexity, full impact — NVD: 6.7 Medium
        assertEquals(6.7, score("CVSS:3.1/AV:L/AC:L/PR:H/UI:N/S:U/C:H/I:H/A:H"), DELTA);
    }

    @Test
    void score_adjacentNetwork_scopeUnchanged_lowImpacts() {
        // Adjacent, AC:H, PR:L, UI:R, all-Low impacts — NVD: 4.3 Medium
        assertEquals(4.3, score("CVSS:3.1/AV:A/AC:H/PR:L/UI:R/S:U/C:L/I:L/A:L"), DELTA);
    }

    // ── Severity rating bands ──────────────────────────────────────────────

    @Test
    void severity_zeroScore_isNone()     { assertEquals("None",     CvssCalculator.severity(0.0)); }
    @Test
    void severity_3_9_isLow()            { assertEquals("Low",      CvssCalculator.severity(3.9)); }
    @Test
    void severity_4_0_isMedium()         { assertEquals("Medium",   CvssCalculator.severity(4.0)); }
    @Test
    void severity_6_9_isMedium()         { assertEquals("Medium",   CvssCalculator.severity(6.9)); }
    @Test
    void severity_7_0_isHigh()           { assertEquals("High",     CvssCalculator.severity(7.0)); }
    @Test
    void severity_9_0_isCritical()       { assertEquals("Critical", CvssCalculator.severity(9.0)); }
    @Test
    void severity_10_0_isCritical()      { assertEquals("Critical", CvssCalculator.severity(10.0)); }

    // ── Roundup function (package-private, tested directly) ───────────────

    @Test
    void roundup_exactTenth_unchanged() {
        assertEquals(7.5, CvssCalculator.roundup(7.5), 1e-9);
    }

    @Test
    void roundup_roundsUpCorrectly() {
        assertEquals(7.5, CvssCalculator.roundup(7.482), 1e-9);
        assertEquals(9.8, CvssCalculator.roundup(9.764), 1e-9);
        assertEquals(6.1, CvssCalculator.roundup(6.007), 1e-9);
    }

    @Test
    void roundup_10_0_staysAt10() {
        assertEquals(10.0, CvssCalculator.roundup(10.0), 1e-9);
    }
}
