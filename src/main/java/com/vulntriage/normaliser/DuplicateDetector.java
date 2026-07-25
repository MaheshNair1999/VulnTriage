package com.vulntriage.normaliser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates a deterministic SHA-256 fingerprint for a finding.
 *
 * Two findings with the same fingerprint are considered identical.
 * The database has a UNIQUE index on fingerprint, so duplicate inserts
 * are silently ignored (INSERT OR IGNORE).
 *
 * Fingerprint input: repositoryId + ":" + filePath + ":" + lineNumber + ":" + ruleId
 * This means:
 *   - Same finding reported by different scan runs → same fingerprint (deduped)
 *   - Same rule fired on different lines → different fingerprint (kept)
 *   - Same line with different rules → different fingerprint (kept)
 */
public class DuplicateDetector {

    /**
     * Generate a SHA-256 fingerprint for the given finding attributes.
     *
     * @param repositoryId  repository the finding belongs to
     * @param filePath      file path of the finding
     * @param lineNumber    line number (null for dependency findings)
     * @param ruleId        rule or CVE ID that triggered the finding
     * @return 64-character lowercase hex SHA-256 digest
     */
    public String generateFingerprint(long repositoryId,
                                      String filePath,
                                      Integer lineNumber,
                                      String ruleId) {
        String input = repositoryId
            + ":" + (filePath  != null ? filePath  : "")
            + ":" + (lineNumber != null ? lineNumber : "")
            + ":" + (ruleId    != null ? ruleId    : "");

        return sha256(input);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the Java spec — this cannot happen
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
