package com.vulntriage.scanner.api;

/**
 * Raw finding as it comes directly from a scanner (Semgrep or Trivy).
 *
 * Both scanners produce structurally different JSON. RawFinding is the
 * common intermediate representation before FindingNormaliser maps it
 * to the domain Finding entity.
 *
 * Fields that don't apply to a scanner are left null.
 * For example, Trivy findings have cveId/packageName/installedVersion/fixedVersion.
 * Semgrep findings have codeSnippet and lineNumber.
 */
public class RawFinding {

    // ── Common fields ──────────────────────────────────────────────────────
    private String source;          // "SEMGREP" or "TRIVY"
    private String ruleId;          // Semgrep check_id / Trivy CVE ID
    private String filePath;        // file scanned
    private Integer lineNumber;     // null for Trivy dependency findings
    private String severity;        // raw string: "WARNING", "ERROR", "MEDIUM" etc.
    private String category;        // normalised category: "xss", "sql_injection" etc.
    private String message;         // human-readable description

    // ── Semgrep-specific ───────────────────────────────────────────────────
    private String codeSnippet;     // the flagged line(s) of code
    private String cwe;             // e.g. "CWE-89" extracted from rule metadata

    // ── Trivy-specific ─────────────────────────────────────────────────────
    private String cveId;
    private String packageName;
    private String installedVersion;
    private String fixedVersion;
    private String cvssVector;  // CVSS:3.1/… extracted from Trivy NVD data, null for non-SCA findings

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String  getSource()                       { return source; }
    public void    setSource(String source)          { this.source = source; }

    public String  getRuleId()                       { return ruleId; }
    public void    setRuleId(String ruleId)          { this.ruleId = ruleId; }

    public String  getFilePath()                     { return filePath; }
    public void    setFilePath(String filePath)      { this.filePath = filePath; }

    public Integer getLineNumber()                   { return lineNumber; }
    public void    setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }

    public String  getSeverity()                     { return severity; }
    public void    setSeverity(String severity)      { this.severity = severity; }

    public String  getCategory()                     { return category; }
    public void    setCategory(String category)      { this.category = category; }

    public String  getMessage()                      { return message; }
    public void    setMessage(String message)        { this.message = message; }

    public String  getCodeSnippet()                  { return codeSnippet; }
    public void    setCodeSnippet(String snippet)    { this.codeSnippet = snippet; }

    public String  getCwe()                          { return cwe; }
    public void    setCwe(String cwe)                { this.cwe = cwe; }

    public String  getCveId()                        { return cveId; }
    public void    setCveId(String cveId)            { this.cveId = cveId; }

    public String  getPackageName()                  { return packageName; }
    public void    setPackageName(String pkg)        { this.packageName = pkg; }

    public String  getInstalledVersion()             { return installedVersion; }
    public void    setInstalledVersion(String v)     { this.installedVersion = v; }

    public String  getFixedVersion()                 { return fixedVersion; }
    public void    setFixedVersion(String v)         { this.fixedVersion = v; }

    public String  getCvssVector()                   { return cvssVector; }
    public void    setCvssVector(String cvssVector)  { this.cvssVector = cvssVector; }

    @Override
    public String toString() {
        return "RawFinding{source=" + source
            + ", ruleId='" + ruleId + "'"
            + ", file='" + filePath + "'"
            + ", line=" + lineNumber
            + ", severity='" + severity + "'}";
    }
}
