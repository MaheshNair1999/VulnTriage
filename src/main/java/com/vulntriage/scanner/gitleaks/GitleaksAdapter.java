package com.vulntriage.scanner.gitleaks;

import com.vulntriage.config.ScanConfig;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerAdapter;
import com.vulntriage.scanner.api.ScannerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Adapter for Gitleaks — secret and credential scanner.
 *
 * Invokes: gitleaks detect --source <path> --report-format json
 *          --report-path <tmpfile> --exit-code 0 --no-banner
 *
 * Gitleaks must be installed and on the system PATH.
 * Exit code 1 normally means "leaks found" — we use --exit-code 0 to suppress
 * that so we can distinguish real errors (exit 126/128) from findings.
 */
public class GitleaksAdapter implements ScannerAdapter {

    private static final Logger log = LoggerFactory.getLogger(GitleaksAdapter.class);

    @Override
    public List<RawFinding> scan(String repositoryPath, ScanConfig config) {
        if (!isAvailable()) {
            throw new ScannerException(
                "Gitleaks is not installed or not on the system PATH. "
                + "Install it from https://github.com/gitleaks/gitleaks/releases and try again.");
        }

        log.info("Starting Gitleaks secrets scan: path={}", repositoryPath);

        Path reportFile;
        try {
            reportFile = Files.createTempFile("gitleaks-", ".json");
        } catch (Exception e) {
            throw new ScannerException("Failed to create temp report file for Gitleaks", e);
        }

        try {
            List<String> cmd = List.of(
                "gitleaks", "detect",
                "--source",          repositoryPath,
                "--report-format",   "json",
                "--report-path",     reportFile.toAbsolutePath().toString(),
                "--exit-code",       "0",    // always exit 0 so we can read the report
                "--no-banner"
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // drain stderr/stdout (merged) so the process doesn't block
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    br.lines().forEach(line -> output.append(line).append("\n"));
                } catch (Exception ignored) {}
            });
            reader.start();

            boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            reader.join(5000);

            if (!finished) {
                process.destroyForcibly();
                throw new ScannerException(
                    "Gitleaks scan timed out after " + config.getTimeoutSeconds() + " seconds.");
            }

            log.debug("Gitleaks exit code: {}", process.exitValue());

            String json = Files.readString(reportFile).trim();
            if (json.isEmpty() || json.equals("null")) {
                log.info("Gitleaks found no secrets.");
                return List.of();
            }

            List<RawFinding> findings = new GitleaksOutputParser().parse(json);
            log.info("Gitleaks scan complete: {} secrets found", findings.size());
            return findings;

        } catch (ScannerException e) {
            throw e;
        } catch (Exception e) {
            throw new ScannerException("Gitleaks scan failed: " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(reportFile); } catch (Exception ignored) {}
        }
    }

    @Override
    public ScannerType getScannerType() {
        return ScannerType.GITLEAKS;
    }

    @Override
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("gitleaks", "version")
                .redirectErrorStream(true)
                .start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
