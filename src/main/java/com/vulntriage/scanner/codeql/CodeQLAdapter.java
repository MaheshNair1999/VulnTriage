package com.vulntriage.scanner.codeql;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Adapter for CodeQL — deep SAST analysis.
 *
 * Two-step process:
 *   1. codeql database create <tmpdb> --language=<lang> --source-root=<path> --overwrite
 *   2. codeql database analyze <tmpdb> codeql/<lang>-queries:codeql-suites/<lang>-security-extended.qls
 *        --format=sarif-latest --output=<sarif> --download
 *
 * CodeQL CLI must be installed and on the system PATH.
 * Query packs are auto-downloaded on first run (requires internet).
 * Database creation can take several minutes for large repos.
 */
public class CodeQLAdapter implements ScannerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CodeQLAdapter.class);

    // CodeQL scans take significantly longer than other scanners
    private static final int CODEQL_TIMEOUT_SECONDS = 3600;

    @Override
    public List<RawFinding> scan(String repositoryPath, ScanConfig config) {
        if (!isAvailable()) {
            throw new ScannerException(
                "CodeQL CLI is not installed or not on the system PATH. "
                + "Download it from https://github.com/github/codeql-action/releases and add it to PATH.");
        }

        String language = detectLanguage(repositoryPath);
        log.info("Starting CodeQL scan: path={} language={}", repositoryPath, language);

        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("codeql-");
        } catch (Exception e) {
            throw new ScannerException("Failed to create temp directory for CodeQL", e);
        }

        Path dbDir    = tmpDir.resolve("db");
        Path sarifOut = tmpDir.resolve("results.sarif");

        try {
            // Step 1: create database
            runCommand(
                List.of("codeql", "database", "create",
                    dbDir.toAbsolutePath().toString(),
                    "--language=" + language,
                    "--source-root=" + repositoryPath,
                    "--overwrite",
                    "--threads=0"),
                "CodeQL database creation",
                CODEQL_TIMEOUT_SECONDS
            );

            // Step 2: analyze
            String querySuite = "codeql/" + language + "-queries:codeql-suites/" + language + "-security-extended.qls";
            runCommand(
                List.of("codeql", "database", "analyze",
                    dbDir.toAbsolutePath().toString(),
                    querySuite,
                    "--format=sarif-latest",
                    "--output=" + sarifOut.toAbsolutePath(),
                    "--download",
                    "--threads=0"),
                "CodeQL analysis",
                CODEQL_TIMEOUT_SECONDS
            );

            String sarif = Files.readString(sarifOut);
            List<RawFinding> findings = new CodeQLOutputParser().parse(sarif);
            log.info("CodeQL scan complete: {} findings", findings.size());
            return findings;

        } catch (ScannerException e) {
            throw e;
        } catch (Exception e) {
            throw new ScannerException("CodeQL scan failed: " + e.getMessage(), e);
        } finally {
            deleteTree(tmpDir);
        }
    }

    @Override
    public ScannerType getScannerType() {
        return ScannerType.CODEQL;
    }

    @Override
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("codeql", "version")
                .redirectErrorStream(true)
                .start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void runCommand(List<String> cmd, String label, int timeoutSeconds) throws Exception {
        log.info("Running: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<String> outputLines = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                br.lines().forEach(line -> {
                    outputLines.add(line);
                    log.debug("[codeql] {}", line);
                });
            } catch (Exception ignored) {}
        });
        reader.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        reader.join(5000);

        if (!finished) {
            process.destroyForcibly();
            throw new ScannerException(label + " timed out after " + timeoutSeconds + " seconds.");
        }

        if (process.exitValue() != 0) {
            String tail = outputLines.size() > 5
                ? String.join("\n", outputLines.subList(outputLines.size() - 5, outputLines.size()))
                : String.join("\n", outputLines);
            throw new ScannerException(label + " failed (exit " + process.exitValue() + "):\n" + tail);
        }
    }

    private String detectLanguage(String repoPath) {
        Map<String, Integer> counts = new HashMap<>();
        try (var stream = Files.walk(Path.of(repoPath), 6)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString().toLowerCase();
                if      (name.endsWith(".java") || name.endsWith(".kt"))          counts.merge("java",       1, Integer::sum);
                else if (name.endsWith(".py"))                                     counts.merge("python",     1, Integer::sum);
                else if (name.endsWith(".js")  || name.endsWith(".ts")
                      || name.endsWith(".jsx") || name.endsWith(".tsx")
                      || name.endsWith(".mjs"))                                    counts.merge("javascript", 1, Integer::sum);
                else if (name.endsWith(".cs"))                                     counts.merge("csharp",     1, Integer::sum);
                else if (name.endsWith(".go"))                                     counts.merge("go",         1, Integer::sum);
                else if (name.endsWith(".rb"))                                     counts.merge("ruby",       1, Integer::sum);
                else if (name.endsWith(".cpp") || name.endsWith(".cc")
                      || name.endsWith(".c")   || name.endsWith(".h")
                      || name.endsWith(".hpp"))                                    counts.merge("cpp",        1, Integer::sum);
                else if (name.endsWith(".swift"))                                  counts.merge("swift",      1, Integer::sum);
            });
        } catch (Exception e) {
            log.warn("Language detection failed, defaulting to java: {}", e.getMessage());
            return "java";
        }

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("java");
    }

    private void deleteTree(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
