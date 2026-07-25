package com.vulntriage.scanner;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.normaliser.DuplicateDetector;
import com.vulntriage.normaliser.FindingNormaliser;
import com.vulntriage.scanner.api.RawFinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NormaliserTest {

    private DuplicateDetector  detector;
    private FindingNormaliser   normaliser;

    @BeforeEach
    void setup() {
        detector   = new DuplicateDetector();
        normaliser = new FindingNormaliser();
    }

    // ── DuplicateDetector ──────────────────────────────────────────────────

    @Test
    void fingerprint_is64CharHexString() {
        String fp = detector.generateFingerprint(1L, "app/views.py", 42, "rule-id");
        assertEquals(64, fp.length());
        assertTrue(fp.matches("[0-9a-f]+"));
    }

    @Test
    void fingerprint_sameInputs_sameOutput() {
        String fp1 = detector.generateFingerprint(1L, "app/views.py", 42, "rule-id");
        String fp2 = detector.generateFingerprint(1L, "app/views.py", 42, "rule-id");
        assertEquals(fp1, fp2);
    }

    @Test
    void fingerprint_differentLine_differentOutput() {
        String fp1 = detector.generateFingerprint(1L, "app/views.py", 42, "rule-id");
        String fp2 = detector.generateFingerprint(1L, "app/views.py", 99, "rule-id");
        assertNotEquals(fp1, fp2);
    }

    @Test
    void fingerprint_differentRepo_differentOutput() {
        String fp1 = detector.generateFingerprint(1L, "app/views.py", 42, "rule-id");
        String fp2 = detector.generateFingerprint(2L, "app/views.py", 42, "rule-id");
        assertNotEquals(fp1, fp2);
    }

    @Test
    void fingerprint_differentRule_differentOutput() {
        String fp1 = detector.generateFingerprint(1L, "app/views.py", 42, "rule-a");
        String fp2 = detector.generateFingerprint(1L, "app/views.py", 42, "rule-b");
        assertNotEquals(fp1, fp2);
    }

    @Test
    void fingerprint_nullLineNumber_doesNotThrow() {
        assertDoesNotThrow(() ->
            detector.generateFingerprint(1L, "requirements.txt", null, "CVE-2023-12345")
        );
    }

    // ── FindingNormaliser ──────────────────────────────────────────────────

    private RawFinding buildRaw() {
        RawFinding raw = new RawFinding();
        raw.setSource      ("SEMGREP");
        raw.setRuleId      ("python.django.security.audit.xss-safe-filter-escape");
        raw.setFilePath    ("app/templates/dashboard.html");
        raw.setLineNumber  (45);
        raw.setSeverity    ("WARNING");
        raw.setCategory    ("xss");
        raw.setMessage     ("Detected user input marked as safe.");
        raw.setCodeSnippet ("{{ user_input|safe }}");
        return raw;
    }

    @Test
    void normalise_setsAllFields() {
        Finding f = normaliser.normalise(buildRaw(), 10L, 20L);

        assertEquals(10L,                  f.getRepositoryId());
        assertEquals(20L,                  f.getScanRunId());
        assertEquals(ScannerType.SEMGREP,  f.getSource());
        assertEquals("app/templates/dashboard.html", f.getFilePath());
        assertEquals(45,                   f.getLineNumber());
        assertEquals(Severity.WARNING,     f.getSeverity());
        assertEquals("xss",               f.getCategory());
        assertFalse(f.getMessage().isBlank());
        assertEquals("{{ user_input|safe }}", f.getCodeSnippet());
        assertNotNull(f.getFingerprint());
        assertEquals(64, f.getFingerprint().length());
        assertNotNull(f.getCreatedAt());
    }

    @Test
    void normalise_generatesDeterministicFingerprint() {
        Finding f1 = normaliser.normalise(buildRaw(), 10L, 20L);
        Finding f2 = normaliser.normalise(buildRaw(), 10L, 99L); // different scanRunId

        // Same fingerprint because repo+file+line+rule are identical
        assertEquals(f1.getFingerprint(), f2.getFingerprint());
    }

    @Test
    void normalise_trivyFinding_noLineNumber() {
        RawFinding raw = new RawFinding();
        raw.setSource      ("TRIVY");
        raw.setRuleId      ("CVE-2023-43665");
        raw.setFilePath    ("requirements.txt");
        raw.setLineNumber  (null);
        raw.setSeverity    ("ERROR");
        raw.setCategory    ("dependency");
        raw.setMessage     ("Django DoS vulnerability");

        Finding f = normaliser.normalise(raw, 1L, 1L);

        assertNull(f.getLineNumber());
        assertEquals(ScannerType.TRIVY, f.getSource());
        assertEquals(Severity.ERROR,    f.getSeverity());
    }

    @Test
    void normaliseAll_returnsMappedList() {
        List<RawFinding> raws = List.of(buildRaw(), buildRaw());
        // Change fingerprints to avoid dedup
        raws.get(0).setLineNumber(1);
        raws.get(1).setLineNumber(2);

        List<Finding> findings = normaliser.normaliseAll(raws, 1L, 1L);
        assertEquals(2, findings.size());
    }

    @Test
    void normalise_unknownSeverity_defaultsToInfo() {
        RawFinding raw = buildRaw();
        raw.setSeverity("GARBAGE");

        Finding f = normaliser.normalise(raw, 1L, 1L);
        assertEquals(Severity.INFO, f.getSeverity());
    }
}
