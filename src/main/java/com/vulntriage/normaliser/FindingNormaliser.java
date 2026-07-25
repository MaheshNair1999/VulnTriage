package com.vulntriage.normaliser;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.scanner.api.RawFinding;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps RawFinding (scanner-specific output) to the domain Finding entity.
 *
 * Also assigns a fingerprint via DuplicateDetector so the repository layer
 * can silently deduplicate findings that appear in multiple scan runs.
 */
public class FindingNormaliser {

    private final DuplicateDetector dedup;

    public FindingNormaliser() {
        this.dedup = new DuplicateDetector();
    }

    /** Package-private for testing with a custom DuplicateDetector. */
    FindingNormaliser(DuplicateDetector dedup) {
        this.dedup = dedup;
    }

    /**
     * Normalise a single RawFinding into a domain Finding.
     *
     * @param raw          raw finding from scanner
     * @param repositoryId ID of the repository being scanned
     * @param scanRunId    ID of this scan run
     * @return normalised Finding ready for persistence
     */
    public Finding normalise(RawFinding raw, long repositoryId, long scanRunId) {
        Finding f = new Finding();

        f.setRepositoryId(repositoryId);
        f.setScanRunId   (scanRunId);
        f.setSource      (ScannerType.valueOf(raw.getSource()));
        f.setRuleId      (clean(raw.getRuleId()));
        f.setFilePath    (clean(raw.getFilePath()));
        f.setLineNumber  (raw.getLineNumber());
        f.setSeverity    (parseSeverity(raw.getSeverity()));
        f.setCategory    (clean(raw.getCategory()));
        f.setMessage     (clean(raw.getMessage()));
        f.setCodeSnippet (raw.getCodeSnippet());
        f.setCwe         (raw.getCwe());
        f.setCreatedAt   (LocalDateTime.now());

        String fingerprint = dedup.generateFingerprint(
            repositoryId,
            raw.getFilePath(),
            raw.getLineNumber(),
            raw.getRuleId()
        );
        f.setFingerprint(fingerprint);

        return f;
    }

    /**
     * Normalise a batch of RawFindings in one call.
     * Skips any raw findings that throw during normalisation (logs the skip).
     */
    public List<Finding> normaliseAll(List<RawFinding> rawFindings,
                                      long repositoryId,
                                      long scanRunId) {
        List<Finding> findings = new ArrayList<>();
        for (RawFinding raw : rawFindings) {
            try {
                findings.add(normalise(raw, repositoryId, scanRunId));
            } catch (Exception e) {
                // Don't fail the entire batch for one bad finding
                System.err.println("Skipping malformed finding: " + e.getMessage());
            }
        }
        return findings;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private Severity parseSeverity(String raw) {
        return Severity.fromString(raw);
    }

    private String clean(String value) {
        return value != null ? value.trim() : "";
    }
}
