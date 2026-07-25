package com.vulntriage.scanner.factory;

import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.scanner.api.ScannerAdapter;
import com.vulntriage.scanner.codeql.CodeQLAdapter;
import com.vulntriage.scanner.gitleaks.GitleaksAdapter;
import com.vulntriage.scanner.semgrep.SemgrepAdapter;
import com.vulntriage.scanner.sonarqube.SonarQubeAdapter;
import com.vulntriage.scanner.trivy.TrivyAdapter;

/**
 * Factory pattern — creates the correct ScannerAdapter for a given ScannerType.
 *
 * Adding a new scanner requires:
 *   1. Adding a value to ScannerType enum
 *   2. Writing an adapter that implements ScannerAdapter
 *   3. Adding one case here
 *
 * No other code changes needed — the pipeline uses ScannerAdapter everywhere.
 */
public class ScannerFactory {

    private ScannerFactory() {}  // utility class, not instantiated

    /**
     * Create an adapter for the given scanner type.
     *
     * @throws IllegalArgumentException if the scanner type is not supported
     */
    public static ScannerAdapter create(ScannerType type) {
        return switch (type) {
            case SEMGREP   -> new SemgrepAdapter();
            case TRIVY     -> new TrivyAdapter();
            case GITLEAKS  -> new GitleaksAdapter();
            case CODEQL    -> new CodeQLAdapter();
            // SonarQube needs URL + token — callers that use the factory should not use SONARQUBE;
            // instantiate SonarQubeAdapter directly with credentials instead.
            case SONARQUBE -> throw new IllegalArgumentException(
                "Use new SonarQubeAdapter(url, token) directly — SONARQUBE requires credentials.");
        };
    }
}
