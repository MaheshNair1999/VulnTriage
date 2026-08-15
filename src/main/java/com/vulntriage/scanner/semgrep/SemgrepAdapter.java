package com.vulntriage.scanner.semgrep;

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
import java.util.stream.Collectors;

/**
 * Adapter pattern implementation for Semgrep (SAST).
 *
 * Invokes: semgrep scan --config <ruleset> --json <repositoryPath>
 *
 * Semgrep must be installed and on the system PATH.
 * Call isAvailable() before scan() to give a friendly error if it's missing.
 */
public class SemgrepAdapter implements ScannerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SemgrepAdapter.class);

    private final SemgrepOutputParser parser;

    // Cached after first resolution so `where`/`which` only runs once per instance
    private String resolvedSemgrepPath = null;

    public SemgrepAdapter() {
        this.parser = new SemgrepOutputParser();
    }

    /** Package-private constructor for testing with a custom parser. */
    SemgrepAdapter(SemgrepOutputParser parser) {
        this.parser = parser;
    }

    @Override
    public List<RawFinding> scan(String repositoryPath, ScanConfig config) {
        // isAvailable() is already checked by ScanStage before calling scan() --
        // skip the redundant check here to avoid a second cold-start penalty.
        log.info("Starting Semgrep scan: path={}, ruleset={}",
            repositoryPath, config.getSemgrepRuleset());

        List<String> command = buildCommand(repositoryPath, config);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false); // keep stderr separate from stdout
            Process process = pb.start();

            // Read stdout (JSON) and stderr (progress/errors) concurrently
            // to avoid the process blocking on full buffers
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
                    "Semgrep scan timed out after " + config.getTimeoutSeconds() + " seconds. " +
                    "Try increasing the timeout in ScanConfig or reducing repository size.");
            }

            int exitCode = process.exitValue();
            log.debug("Semgrep exit code: {}", exitCode);

            if (stderr.length() > 0) {
                log.debug("Semgrep stderr: {}", stderr.toString().trim());
            }

            String output = stdout.toString().trim();
            if (output.isEmpty()) {
                log.warn("Semgrep produced no output (exit code {})", exitCode);
                return new ArrayList<>();
            }

            // Tell the parser where the repo is so it can read files directly
            parser.setRepositoryPath(repositoryPath);
            List<RawFinding> findings = parser.parse(output);
            log.info("Semgrep scan complete: {} findings found", findings.size());
            return findings;

        } catch (ScannerException e) {
            throw e;
        } catch (Exception e) {
            throw new ScannerException("Semgrep scan failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ScannerType getScannerType() {
        return ScannerType.SEMGREP;
    }

    @Override
    public boolean isAvailable() {
        try {
            // On Windows, ProcessBuilder does not always inherit the full user PATH
            // (e.g. when launched from IntelliJ). We resolve the executable once and
            // cache the result so subsequent calls (scan, buildCommand) don't re-run `where`.
            String semgrepPath = semgrepPath();
            Process p = new ProcessBuilder(semgrepPath, "--version")
                .redirectErrorStream(true)
                .start();
            // 30-second timeout: Semgrep's Python/uv runtime can take 6-10 s on cold start
            // (first launch after install or after a long idle). 5 s was too short and caused
            // isAvailable() to return false on the first scan, silently skipping it.
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the resolved semgrep path, computing it once and caching the result. */
    private synchronized String semgrepPath() {
        if (resolvedSemgrepPath != null) return resolvedSemgrepPath;
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            ProcessBuilder pb = isWindows
                ? new ProcessBuilder("where", "semgrep")
                : new ProcessBuilder("which", "semgrep");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        resolvedSemgrepPath = line.trim();
                        return resolvedSemgrepPath;
                    }
                }
            }
        } catch (Exception ignored) {}
        resolvedSemgrepPath = "semgrep"; // fall back to bare name
        return resolvedSemgrepPath;
    }

    private List<String> buildCommand(String repositoryPath, ScanConfig config) {
        List<String> cmd = new ArrayList<>();
        cmd.add(semgrepPath());
        cmd.add("scan");
        cmd.add("--config");
        cmd.add(config.getSemgrepRuleset());
        cmd.add("--json");
        cmd.add("--no-git-ignore");   // scan all files, not just tracked ones
        cmd.add(repositoryPath);
        return cmd;
    }
}
