package com.vulntriage.domain.enums;

/**
 * The supported scanner backends.
 * New scanners are added here and wired up in ScannerFactory.
 */
public enum ScannerType {
    SEMGREP,
    TRIVY,
    GITLEAKS,
    CODEQL,
    SONARQUBE
}
