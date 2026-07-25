package com.vulntriage.scanner.api;

import com.vulntriage.config.ScanConfig;
import com.vulntriage.domain.enums.ScannerType;

import java.util.List;

/**
 * Adapter pattern — uniform interface for all scanner backends.
 *
 * Each scanner (Semgrep, Trivy, future tools) implements this interface.
 * The pipeline talks only to this interface, so scanners can be swapped
 * or added without touching pipeline logic.
 *
 * Implementations are responsible for:
 *   - invoking the scanner binary via ProcessBuilder
 *   - capturing its JSON output
 *   - parsing the output into RawFinding objects
 *   - throwing ScannerException on failure
 */
public interface ScannerAdapter {

    /**
     * Run the scanner against the given repository path.
     *
     * @param repositoryPath absolute path to the repository root
     * @param config         scan settings (ruleset, timeout, etc.)
     * @return list of raw findings (may be empty, never null)
     * @throws ScannerException if the scanner binary fails or output is unparseable
     */
    List<RawFinding> scan(String repositoryPath, ScanConfig config);

    /**
     * The scanner type this adapter handles.
     * Used by ScannerFactory and for labelling findings with their source.
     */
    ScannerType getScannerType();

    /**
     * Check whether the scanner binary is available on the system PATH.
     * Used to give a clear error message before attempting a scan.
     */
    boolean isAvailable();
}
