package com.vulntriage.scanner.sonarqube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulntriage.config.ScanConfig;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerAdapter;
import com.vulntriage.scanner.api.ScannerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Adapter for SonarQube — code quality and security analysis via server.
 *
 * Requires:
 *   - A running SonarQube server (community edition is free)
 *   - sonar-scanner CLI installed and on PATH
 *   - A user token generated in the SonarQube UI (Administration → Security → Users)
 *
 * Flow:
 *   1. Run sonar-scanner to push the analysis to the server
 *   2. Fetch issues from the REST API: /api/issues/search
 *
 * The project key is derived from the repository directory name.
 */
public class SonarQubeAdapter implements ScannerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SonarQubeAdapter.class);

    private final String serverUrl;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();

    public SonarQubeAdapter(String serverUrl, String token) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.token     = token;
    }

    @Override
    public List<RawFinding> scan(String repositoryPath, ScanConfig config) {
        if (!isAvailable()) {
            throw new ScannerException(
                "sonar-scanner is not installed or not on the system PATH. "
                + "Download it from https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/scanners/sonarscanner/");
        }

        // Derive a stable project key from the repo directory name
        String repoName   = java.nio.file.Path.of(repositoryPath).getFileName().toString();
        String projectKey = repoName.toLowerCase().replaceAll("[^a-z0-9_:.-]", "-");
        log.info("Starting SonarQube scan: path={} projectKey={} server={}", repositoryPath, projectKey, serverUrl);

        ensureProjectExists(projectKey, repoName);
        String ceTaskId = runSonarScanner(repositoryPath, projectKey, config.getTimeoutSeconds());
        waitForAnalysis(ceTaskId);

        List<RawFinding> findings = fetchIssues(projectKey);
        log.info("SonarQube scan complete: {} issues fetched", findings.size());
        return findings;
    }

    @Override
    public ScannerType getScannerType() {
        return ScannerType.SONARQUBE;
    }

    @Override
    public boolean isAvailable() {
        try {
            List<String> cmd = isWindows()
                ? List.of("cmd", "/c", "sonar-scanner", "--version")
                : List.of("sonar-scanner", "--version");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /** Creates the project in SonarQube if it doesn't already exist. */
    private void ensureProjectExists(String projectKey, String displayName) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(
            (token + ":").getBytes(StandardCharsets.UTF_8));

        // Check if project already exists
        try {
            HttpRequest check = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + "/api/projects/search?projects=" + projectKey))
                .header("Authorization", authHeader)
                .GET().build();
            HttpResponse<String> res = client.send(check, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(res.body());
            JsonNode components = root.path("components");
            if (components.isArray() && components.size() > 0) {
                log.info("SonarQube project '{}' already exists", projectKey);
                return;
            }
        } catch (Exception e) {
            log.warn("Could not check if project exists: {}", e.getMessage());
        }

        // Create project
        try {
            String body = "project=" + projectKey + "&name=" + java.net.URLEncoder.encode(displayName, StandardCharsets.UTF_8) + "&visibility=private";
            HttpRequest create = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + "/api/projects/create"))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> res = client.send(create, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                log.info("Created SonarQube project '{}'", projectKey);
            } else {
                log.warn("Could not create SonarQube project (HTTP {}): {}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            log.warn("Failed to create SonarQube project: {}", e.getMessage());
        }
    }

    /**
     * Runs sonar-scanner and returns the CE task ID from the output so we can
     * poll for completion before fetching issues.
     */
    private String runSonarScanner(String repoPath, String projectKey, int timeoutSeconds) {
        // On Windows, ProcessBuilder won't find .bat files — run via cmd /c
        List<String> cmd = isWindows()
            ? List.of("cmd", "/c", "sonar-scanner",
                "-Dsonar.projectKey="   + projectKey,
                "-Dsonar.sources=.",
                "-Dsonar.host.url="     + serverUrl,
                "-Dsonar.token="        + token,
                "-Dsonar.scm.disabled=true",
                "-Dsonar.exclusions=**/.git/**,**/*.png,**/*.jpg,**/*.jpeg,**/*.gif,**/*.ico,**/*.pack,**/*.idx")
            : List.of("sonar-scanner",
                "-Dsonar.projectKey="   + projectKey,
                "-Dsonar.sources=.",
                "-Dsonar.host.url="     + serverUrl,
                "-Dsonar.token="        + token,
                "-Dsonar.scm.disabled=true",
                "-Dsonar.exclusions=**/.git/**,**/*.png,**/*.jpg,**/*.jpeg,**/*.gif,**/*.ico,**/*.pack,**/*.idx");

        log.info("Running sonar-scanner for project '{}'", projectKey);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new java.io.File(repoPath));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<String> outputLines = new ArrayList<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    br.lines().forEach(line -> {
                        outputLines.add(line);
                        log.info("[sonar-scanner] {}", line);
                    });
                } catch (Exception ignored) {}
            });
            reader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            reader.join(5000);

            if (!finished) {
                process.destroyForcibly();
                throw new ScannerException("sonar-scanner timed out after " + timeoutSeconds + " seconds.");
            }

            if (process.exitValue() != 0) {
                String tail = outputLines.size() > 10
                    ? String.join("\n", outputLines.subList(outputLines.size() - 10, outputLines.size()))
                    : String.join("\n", outputLines);
                throw new ScannerException("sonar-scanner failed (exit " + process.exitValue() + "):\n" + tail);
            }

            // Extract CE task ID from line like:
            // "More about the report processing at http://localhost:9000/api/ce/task?id=XXXXX"
            return outputLines.stream()
                .filter(l -> l.contains("/api/ce/task?id="))
                .findFirst()
                .map(l -> l.substring(l.indexOf("?id=") + 4).trim())
                .orElse(null);

        } catch (ScannerException e) {
            throw e;
        } catch (Exception e) {
            throw new ScannerException("sonar-scanner execution failed: " + e.getMessage(), e);
        }
    }

    /** Polls /api/ce/task until the analysis is complete (up to 5 minutes). */
    private void waitForAnalysis(String ceTaskId) {
        if (ceTaskId == null) {
            log.warn("No CE task ID found — waiting 10s as fallback before fetching issues");
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}
            return;
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(
            (token + ":").getBytes(StandardCharsets.UTF_8));
        String url = serverUrl + "/api/ce/task?id=" + ceTaskId;

        log.info("Waiting for SonarQube analysis task {} to complete…", ceTaskId);
        long deadline = System.currentTimeMillis() + 900_000; // 15 min max

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(3000);
                if (Thread.currentThread().isInterrupted()) {
                    log.info("SonarQube polling interrupted by user stop request");
                    return;
                }
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .GET().build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                JsonNode root   = mapper.readTree(res.body());
                String   status = root.path("task").path("status").asText("");
                log.info("CE task status: {}", status);
                if (status.equals("SUCCESS")) return;
                if (status.equals("FAILED") || status.equals("CANCELLED")) {
                    throw new ScannerException("SonarQube analysis task " + status.toLowerCase());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("SonarQube polling interrupted by user stop request");
                return;
            } catch (ScannerException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Error polling CE task: {}", e.getMessage());
            }
        }
        log.warn("Timed out waiting for SonarQube analysis — fetching whatever is available");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private List<RawFinding> fetchIssues(String projectKey) {
        List<RawFinding> findings = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

        String authHeader = "Basic " + Base64.getEncoder().encodeToString(
            (token + ":").getBytes(StandardCharsets.UTF_8));

        int page = 1;
        int pageSize = 500;

        while (true) {
            String url = serverUrl + "/api/issues/search"
                + "?projectKeys=" + projectKey
                + "&types=VULNERABILITY,BUG,CODE_SMELL"
                + "&resolved=false"
                + "&ps=" + pageSize
                + "&p=" + page;

            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .GET()
                    .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new ScannerException(
                        "SonarQube API returned HTTP " + response.statusCode()
                        + ". Check your server URL and token.");
                }

                JsonNode root   = mapper.readTree(response.body());
                JsonNode issues = root.path("issues");
                int total       = root.path("total").asInt(0);

                if (!issues.isArray()) break;

                for (JsonNode issue : issues) {
                    try {
                        findings.add(parseIssue(issue, projectKey));
                    } catch (Exception e) {
                        log.warn("Skipping malformed SonarQube issue: {}", e.getMessage());
                    }
                }

                if (findings.size() >= total || issues.isEmpty()) break;
                page++;

            } catch (ScannerException e) {
                throw e;
            } catch (Exception e) {
                throw new ScannerException("Failed to fetch SonarQube issues: " + e.getMessage(), e);
            }
        }

        return findings;
    }

    private RawFinding parseIssue(JsonNode issue, String projectKey) {
        RawFinding f = new RawFinding();
        f.setSource("SONARQUBE");

        f.setRuleId(issue.path("rule").asText("unknown"));
        f.setSeverity(mapSeverity(issue.path("severity").asText("MAJOR")));
        f.setMessage(issue.path("message").asText("No message"));
        f.setCategory(issue.path("type").asText("BUG").toLowerCase().replace("_", "-"));

        // component is "projectKey:relative/path/to/File.java"
        String component = issue.path("component").asText("");
        String filePath  = component.startsWith(projectKey + ":")
            ? component.substring(projectKey.length() + 1)
            : component;
        f.setFilePath(filePath);

        int line = issue.path("line").asInt(0);
        if (line > 0) f.setLineNumber(line);

        // Extract CWE from tags if present
        JsonNode tags = issue.path("tags");
        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                String t = tag.asText("");
                if (t.startsWith("cwe")) {
                    f.setCwe("CWE-" + t.replaceAll("[^0-9]", ""));
                    break;
                }
            }
        }

        return f;
    }

    private static String mapSeverity(String sonarSeverity) {
        return switch (sonarSeverity.toUpperCase()) {
            case "BLOCKER", "CRITICAL" -> "ERROR";
            case "MAJOR"               -> "WARNING";
            default                    -> "INFO";
        };
    }
}
