package com.vulntriage.cvss;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CvssVector}.
 *
 * Covers: successful parsing of well-formed vectors, rejection of every class
 * of malformed input (null, blank, wrong prefix, unknown metric, unknown value,
 * missing mandatory metric), and the convenience accessors.
 */
class CvssVectorTest {

    // ── Happy-path parsing ─────────────────────────────────────────────────

    @Test
    void parse_fullCriticalVector_allMetricsCorrect() {
        CvssVector v = CvssVector.parse("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H");
        assertEquals("N", v.av());
        assertEquals("L", v.ac());
        assertEquals("N", v.pr());
        assertEquals("N", v.ui());
        assertEquals("U", v.s());
        assertEquals("H", v.c());
        assertEquals("H", v.i());
        assertEquals("H", v.a());
        assertFalse(v.isScopeChanged());
    }

    @Test
    void parse_scopeChangedVector_isScopeChangedTrue() {
        CvssVector v = CvssVector.parse("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H");
        assertTrue(v.isScopeChanged());
        assertEquals("C", v.s());
    }

    @Test
    void parse_physicalAccessVector_parsesCorrectly() {
        CvssVector v = CvssVector.parse("CVSS:3.1/AV:P/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H");
        assertEquals("P", v.av());
    }

    @Test
    void parse_userInteractionRequired_parsesCorrectly() {
        CvssVector v = CvssVector.parse("CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N");
        assertEquals("R", v.ui());
        assertEquals("L", v.c());
        assertEquals("N", v.a());
    }

    @Test
    void parse_allLowImpacts_parsesCorrectly() {
        CvssVector v = CvssVector.parse("CVSS:3.1/AV:L/AC:H/PR:H/UI:R/S:U/C:L/I:L/A:L");
        assertEquals("L", v.c());
        assertEquals("L", v.i());
        assertEquals("L", v.a());
    }

    @Test
    void parse_noneImpacts_parsesCorrectly() {
        CvssVector v = CvssVector.parse("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N");
        assertEquals("N", v.c());
        assertEquals("N", v.i());
        assertEquals("N", v.a());
    }

    @Test
    void toString_roundTripsVector() {
        String original = "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H";
        assertEquals(original, CvssVector.parse(original).toString());
    }

    // ── Rejection of malformed input ───────────────────────────────────────

    @Test
    void parse_null_throwsCvssException() {
        CvssException ex = assertThrows(CvssException.class, () -> CvssVector.parse(null));
        assertNotNull(ex.getOffendingToken());
    }

    @Test
    void parse_blank_throwsCvssException() {
        assertThrows(CvssException.class, () -> CvssVector.parse("   "));
    }

    @Test
    void parse_wrongPrefix_throwsWithOffendingToken() {
        CvssException ex = assertThrows(CvssException.class,
            () -> CvssVector.parse("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"));
        assertTrue(ex.getMessage().contains("CVSS:3.0"));
    }

    @Test
    void parse_noPrefixAtAll_throwsCvssException() {
        assertThrows(CvssException.class,
            () -> CvssVector.parse("AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"));
    }

    @Test
    void parse_unknownMetricKey_throwsWithOffendingToken() {
        CvssException ex = assertThrows(CvssException.class,
            () -> CvssVector.parse("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H/XX:Y"));
        assertEquals("XX", ex.getOffendingToken());
    }

    @Test
    void parse_invalidMetricValue_throwsWithOffendingToken() {
        // AV:Z is not a valid Attack Vector value
        CvssException ex = assertThrows(CvssException.class,
            () -> CvssVector.parse("CVSS:3.1/AV:Z/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"));
        assertEquals("Z", ex.getOffendingToken());
    }

    @Test
    void parse_missingMandatoryMetric_throwsWithMetricName() {
        // Omit Scope (S)
        CvssException ex = assertThrows(CvssException.class,
            () -> CvssVector.parse("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/C:H/I:H/A:H"));
        assertEquals("S", ex.getOffendingToken());
    }

    @Test
    void parse_malformedTokenNoColon_throwsCvssException() {
        assertThrows(CvssException.class,
            () -> CvssVector.parse("CVSS:3.1/AVN/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"));
    }
}
