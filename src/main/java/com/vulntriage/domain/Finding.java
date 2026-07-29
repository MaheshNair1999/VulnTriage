package com.vulntriage.domain;

import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import java.time.LocalDateTime;

/**
 * A single vulnerability finding produced by a scanner (Semgrep or Trivy).
 *
 * This is the central entity of the system. Every other entity (ManualReview,
 * LlmResult) references a Finding by ID.
 *
 * The fingerprint field enables deduplication: two findings with the same
 * repository + file + line + ruleId are considered identical and only stored once.
 */
public class Finding {

    private long         id;
    private long         scanRunId;
    private long         repositoryId;
    private ScannerType  source;       // SEMGREP | TRIVY
    private String       ruleId;
    private String       filePath;
    private Integer      lineNumber;   // null for Trivy dependency findings
    private Severity     severity;
    private String       category;    // e.g. "xss", "sql_injection"
    private String       message;
    private String       codeSnippet; // null for Trivy findings
    private String       cwe;         // e.g. "CWE-89" — null if not reported by scanner
    private String       fingerprint; // SHA-256 of repositoryId+filePath+lineNumber+ruleId
    private LocalDateTime createdAt;
    private String       cvssVector; // CVSS:3.1/… — null for non-SCA findings
    private Double       cvssScore;  // computed base score; null when vector absent or unparseable

    // ── Constructors ───────────────────────────────────────────────────────

    public Finding() {}

    // ── Getters & Setters ──────────────────────────────────────────────────

    public long getId()                           { return id; }
    public void setId(long id)                    { this.id = id; }

    public long getScanRunId()                    { return scanRunId; }
    public void setScanRunId(long scanRunId)       { this.scanRunId = scanRunId; }

    public long getRepositoryId()                 { return repositoryId; }
    public void setRepositoryId(long repositoryId){ this.repositoryId = repositoryId; }

    public ScannerType getSource()                { return source; }
    public void setSource(ScannerType source)     { this.source = source; }

    public String getRuleId()                     { return ruleId; }
    public void setRuleId(String ruleId)          { this.ruleId = ruleId; }

    public String getFilePath()                   { return filePath; }
    public void setFilePath(String filePath)       { this.filePath = filePath; }

    public Integer getLineNumber()                { return lineNumber; }
    public void setLineNumber(Integer lineNumber)  { this.lineNumber = lineNumber; }

    public Severity getSeverity()                 { return severity; }
    public void setSeverity(Severity severity)    { this.severity = severity; }

    public String getCategory()                   { return category; }
    public void setCategory(String category)      { this.category = category; }

    public String getMessage()                    { return message; }
    public void setMessage(String message)        { this.message = message; }

    public String getCodeSnippet()                { return codeSnippet; }
    public void setCodeSnippet(String codeSnippet){ this.codeSnippet = codeSnippet; }

    public String getCwe()                        { return cwe; }
    public void setCwe(String cwe)                { this.cwe = cwe; }

    public String getFingerprint()                { return fingerprint; }
    public void setFingerprint(String fingerprint){ this.fingerprint = fingerprint; }

    public LocalDateTime getCreatedAt()                   { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)     { this.createdAt = createdAt; }

    public String getCvssVector()                         { return cvssVector; }
    public void setCvssVector(String cvssVector)          { this.cvssVector = cvssVector; }

    public Double getCvssScore()                          { return cvssScore; }
    public void setCvssScore(Double cvssScore)            { this.cvssScore = cvssScore; }

    @Override
    public String toString() {
        return "Finding{id=" + id
            + ", source=" + source
            + ", ruleId='" + ruleId + "'"
            + ", file='" + filePath + "'"
            + ", line=" + lineNumber
            + ", severity=" + severity + "}";
    }
}
