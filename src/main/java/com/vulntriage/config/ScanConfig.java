package com.vulntriage.config;

/**
 * Configuration for a single scan execution.
 * Passed to ScannerAdapter.scan() so callers can customise behaviour
 * without changing the adapter implementation.
 */
public class ScanConfig {

    private String  semgrepRuleset  = "p/security-audit";
    private int     timeoutSeconds  = 300;
    private int     maxFindings     = 10_000;
    private boolean runSemgrep      = false;
    private boolean runTrivy        = false;
    private boolean runGitleaks     = false;
    private boolean runCodeql       = false;
    private boolean runSonarqube    = false;
    private String  sonarUrl        = "";
    private String  sonarToken      = "";

    // ── Factory methods ────────────────────────────────────────────────────

    public static ScanConfig defaults() {
        ScanConfig c = new ScanConfig();
        c.runSemgrep = true;
        c.runTrivy   = true;
        return c;
    }

    public static ScanConfig semgrepOnly() {
        ScanConfig c = new ScanConfig();
        c.runSemgrep = true;
        return c;
    }

    public static ScanConfig trivyOnly() {
        ScanConfig c = new ScanConfig();
        c.runTrivy = true;
        return c;
    }

    public static ScanConfig gitleaksOnly() {
        ScanConfig c = new ScanConfig();
        c.runGitleaks = true;
        return c;
    }

    public static ScanConfig codeqlOnly() {
        ScanConfig c = new ScanConfig();
        c.runCodeql = true;
        return c;
    }

    public static ScanConfig sonarqubeOnly(String url, String token) {
        ScanConfig c = new ScanConfig();
        c.runSonarqube = true;
        c.sonarUrl     = url;
        c.sonarToken   = token;
        return c;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getSemgrepRuleset()               { return semgrepRuleset; }
    public void   setSemgrepRuleset(String ruleset)  { this.semgrepRuleset = ruleset; }

    public int  getTimeoutSeconds()                  { return timeoutSeconds; }
    public void setTimeoutSeconds(int t)             { this.timeoutSeconds = t; }

    public int  getMaxFindings()                     { return maxFindings; }
    public void setMaxFindings(int max)              { this.maxFindings = max; }

    public boolean isRunSemgrep()                    { return runSemgrep; }
    public void    setRunSemgrep(boolean run)        { this.runSemgrep = run; }

    public boolean isRunTrivy()                      { return runTrivy; }
    public void    setRunTrivy(boolean run)          { this.runTrivy = run; }

    public boolean isRunGitleaks()                   { return runGitleaks; }
    public void    setRunGitleaks(boolean run)       { this.runGitleaks = run; }

    public boolean isRunCodeql()                     { return runCodeql; }
    public void    setRunCodeql(boolean run)         { this.runCodeql = run; }

    public boolean isRunSonarqube()                  { return runSonarqube; }
    public void    setRunSonarqube(boolean run)      { this.runSonarqube = run; }

    public String getSonarUrl()                      { return sonarUrl; }
    public void   setSonarUrl(String url)            { this.sonarUrl = url; }

    public String getSonarToken()                    { return sonarToken; }
    public void   setSonarToken(String token)        { this.sonarToken = token; }
}
