package com.vulntriage.scanner.trivy;

import com.vulntriage.config.ScanConfig;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerAdapter;
import com.vulntriage.scanner.api.ScannerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Adapter pattern implementation for Trivy (SCA).
 *
 * Invokes: trivy fs --format json --quiet <repositoryPath>
 *
 * Trivy must be installed and on the system PATH.
 * Call isAvailable() before scan() to give a friendly error if it's missing.
 */
public class TrivyAdapter implements ScannerAdapter {

    private static final Logger log = LoggerFactory.getLogger(TrivyAdapter.class);

    private final TrivyOutputParser parser;

    public TrivyAdapter() {
        this.parser = new TrivyOutputParser();
    }

    /** Package-private constructor for testing with a custom parser. */
    TrivyAdapter(TrivyOutputParser parser) {
        this.parser = parser;
    }

    @Override
    public List<RawFinding> scan(String repositoryPath, ScanConfig config) {
        if (!isAvailable()) {
            throw new ScannerException(
                "Trivy is not installed or not on the system PATH. " +
                "Install it from https://trivy.dev and try again.");
        }

        log.info("Starting Trivy SCA scan: path={}", repositoryPath);

        List<String> command = buildCommand(repositoryPath);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    reader.lines().forEach(line -> stdout.append(line).append("\n"));
                } catch (Exception ignored) {}
            });

            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    reader.lines().forEach(line -> stderr.append(line).append("\n"));
                } catch (Exception ignored) {}
            });

            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);

            stdoutReader.join(5000);
            stderrReader.join(5000);

            if (!finished) {
                process.destroyForcibly();
                throw new ScannerException(
                    "Trivy scan timed out after " + config.getTimeoutSeconds() + " seconds.");
            }

            log.debug("Trivy exit code: {}", process.exitValue());

            String output = stdout.toString().trim();
            if (output.isEmpty()) {
                log.warn("Trivy produced no output");
                return new ArrayList<>();
            }

            List<RawFinding> findings = parser.parse(output);
            log.info("Trivy scan complete: {} dependency vulnerabilities found", findings.size());
            return findings;

        } catch (ScannerException e) {
            throw e;
        } catch (Exception e) {
            throw new ScannerException("Trivy scan failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ScannerType getScannerType() {
        return ScannerType.TRIVY;
    }

    @Override
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("trivy", "--version")
                .redirectErrorStream(true)
                .start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> buildCommand(String repositoryPath) {
        return List.of(
            "trivy", "fs",
            "--format", "json",
            "--quiet",          // suppress progress bars so stdout is clean JSON
            repositoryPath
        );
    }
}
