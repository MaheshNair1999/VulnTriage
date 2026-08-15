package com.vulntriage.experiment;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.LlmResult;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.ollama.OllamaTriageStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Supplementary experiment: context-enriched prompt (v2.0).
 *
 * Re-triages all XSS/template-injection findings from the existing database
 * using a prompt that includes the full source file, enabling the model to
 * reason about Django auto-escaping, access control, and variable provenance.
 *
 * Results are stored in llm_results with prompt_version="v2.0" alongside
 * the original v1.0 results — nothing is overwritten.
 *
 * Usage:
 *   java -Dvulntriage.db.url=jdbc:sqlite:<path-to-db>
 *        -cp vulntriage.jar
 *        com.vulntriage.experiment.ContextExperimentRunner
 *        <repos-base-path> [ollama-url] [model]
 *
 * Example:
 *   java -Dvulntriage.db.url=jdbc:sqlite:C:/Users/mahesh/Desktop/CyberSecurity/vulntriage SE/projects/vulntriage.db
 *        -cp target/vulntriage-*.jar
 *        com.vulntriage.experiment.ContextExperimentRunner
 *        "C:/Users/mahesh/Desktop/CyberSecurity/repos"
 *        http://localhost:11434
 *        qwen3:14b
 */
public class ContextExperimentRunner {

    // XSS / template-injection rule patterns (lower-case match)
    private static final List<String> XSS_PATTERNS = List.of(
        "xss", "template", "blocktrans", "var-in", "autoescape", "noescape"
    );

    // Lines of context above and below the flagged line to include
    private static final int CONTEXT_LINES_BEFORE = 80;
    private static final int CONTEXT_LINES_AFTER  = 80;
    // Lines from file header (imports, decorators, class def) to always include
    private static final int HEADER_LINES = 30;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ContextExperimentRunner <repos-base-path> [ollama-url] [model] [max-fp]");
            System.err.println("  max-fp: max number of FP findings to include (default: all). TPs always all included.");
            System.exit(1);
        }

        String reposBasePath = args[0];
        String ollamaUrl     = args.length > 1 ? args[1] : OllamaTriageStrategy.DEFAULT_URL;
        String model         = args.length > 2 ? args[2] : "qwen3:14b";
        int    maxFp         = args.length > 3 ? Integer.parseInt(args[3]) : Integer.MAX_VALUE;

        String dbUrl = System.getProperty("vulntriage.db.url", "jdbc:sqlite:vulntriage.db");
        System.out.println("DB  : " + dbUrl);
        System.out.println("Repos base: " + reposBasePath);
        System.out.println("Ollama: " + ollamaUrl + "  Model: " + model);

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            conn.createStatement().execute("PRAGMA foreign_keys = ON");

            // 1. Load repository id -> local folder mapping
            Map<Long, String> repoFolders = loadRepoFolders(conn, reposBasePath);
            System.out.println("Repositories mapped: " + repoFolders);

            // 2. Load XSS findings with human verdict; sample FPs if max-fp set
            List<Object[]> findings = loadXssFindings(conn, maxFp);
            System.out.println("XSS findings to process: " + findings.size());

            // 3. Create evaluation run entry for v2.0
            long evalRunId = createEvaluationRun(conn,
                "Context-Enriched Experiment v2.0",
                "Re-triage of XSS/template findings with full source file context");
            System.out.println("Evaluation run ID: " + evalRunId);

            // 4. Run triage
            OllamaTriageStrategy strategy = new OllamaTriageStrategy(ollamaUrl, model);
            if (!strategy.isAvailable()) {
                System.err.println("ERROR: Ollama not reachable or model not available. Aborting.");
                System.exit(1);
            }

            int done = 0, skipped = 0, fileMissing = 0;
            Map<String, Integer> humanToLlm = new LinkedHashMap<>();

            for (Object[] row : findings) {
                Finding f           = (Finding) row[0];
                String  humanVerdict = (String)  row[1];

                String repoFolder = repoFolders.get(f.getRepositoryId());
                if (repoFolder == null) {
                    System.out.printf("[%d/%d] SKIP  finding=%d — repo %d not mapped%n",
                        done + skipped + 1, findings.size(), f.getId(), f.getRepositoryId());
                    skipped++;
                    continue;
                }

                String fullFileContent = readFile(reposBasePath, repoFolder, f.getFilePath(), f.getLineNumber());
                if (fullFileContent == null) {
                    fileMissing++;
                    fullFileContent = "(file not found — snippet-only fallback)\n" +
                        (f.getCodeSnippet() != null ? f.getCodeSnippet() : "");
                }

                try {
                    TriageResult result = strategy.triageWithContext(f, fullFileContent);

                    LlmResult lr = new LlmResult();
                    lr.setFindingId      (f.getId());
                    lr.setEvaluationRunId(evalRunId);
                    lr.setLlmVerdict     (result.getVerdict());
                    lr.setConfidence     (result.getConfidence());
                    lr.setReasoning      (result.getReasoning());
                    lr.setRemediation    (result.getRemediation());
                    lr.setModelUsed      (model);
                    lr.setPromptVersion  ("v2.0");
                    saveLlmResult(conn, lr);

                    String key = humanVerdict + "->" + result.getVerdict().name();
                    humanToLlm.merge(key, 1, Integer::sum);
                    done++;

                    if (done % 10 == 0) {
                        System.out.printf("[%d/%d] processed (file_missing=%d, skipped=%d)%n",
                            done, findings.size(), fileMissing, skipped);
                    }

                } catch (Exception e) {
                    System.err.printf("ERROR finding=%d: %s%n", f.getId(), e.getMessage());
                    skipped++;
                }
            }

            // 5. Print comparison summary
            System.out.println("\n=== CONTEXT-ENRICHED EXPERIMENT RESULTS (v2.0) ===");
            System.out.printf("Processed: %d  |  Skipped: %d  |  File-missing: %d%n",
                done, skipped, fileMissing);
            System.out.println("\nConfusion (human -> llm_v2):");
            humanToLlm.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf("  %-25s : %d%n", e.getKey(), e.getValue()));

            printMetrics(conn, evalRunId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<Long, String> loadRepoFolders(Connection conn, String reposBasePath)
            throws SQLException {
        Map<Long, String> map = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM repositories")) {
            while (rs.next()) {
                long   id   = rs.getLong("id");
                String name = rs.getString("name").toLowerCase();
                // Try exact name match first, then without dashes
                Path candidate = Paths.get(reposBasePath, name);
                if (!Files.isDirectory(candidate)) {
                    // Try common variations: defectdojo -> django-DefectDojo
                    candidate = findRepoFolder(Paths.get(reposBasePath), name);
                }
                if (candidate != null && Files.isDirectory(candidate)) {
                    map.put(id, candidate.toAbsolutePath().toString());
                } else {
                    System.out.println("WARNING: could not find local folder for repo: " + name);
                }
            }
        }
        return map;
    }

    private static Path findRepoFolder(Path base, String repoName) {
        try {
            return Files.list(base)
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().toLowerCase().contains(
                    repoName.replace("-", "").replace("_", "")))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Loads XSS/template findings. Returns list of [Finding, humanVerdict] pairs.
     * All TPs are always included. FPs are randomly sampled up to maxFp.
     * REVIEW findings are excluded (ambiguous ground truth).
     */
    private static List<Object[]> loadXssFindings(Connection conn, int maxFp) throws SQLException {
        List<Object[]> tps = new ArrayList<>();
        List<Object[]> fps = new ArrayList<>();

        String sql = """
            SELECT f.id, f.repository_id, f.rule_id, f.file_path, f.line_number,
                   f.severity, f.category, f.message, f.code_snippet, f.source,
                   mr.verdict as human_verdict
            FROM findings f
            JOIN manual_reviews mr ON f.id = mr.finding_id
            WHERE f.source = 'SEMGREP'
            ORDER BY f.id
            """;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String ruleId = rs.getString("rule_id").toLowerCase();
                boolean isXss = XSS_PATTERNS.stream().anyMatch(ruleId::contains);
                if (!isXss) continue;

                String verdict = rs.getString("human_verdict");
                if ("REVIEW".equals(verdict)) continue; // exclude ambiguous

                Finding f = new Finding();
                f.setId           (rs.getLong  ("id"));
                f.setRepositoryId (rs.getLong  ("repository_id"));
                f.setRuleId       (rs.getString("rule_id"));
                f.setFilePath     (rs.getString("file_path"));
                f.setLineNumber   (rs.getObject("line_number") != null
                    ? rs.getInt("line_number") : null);
                String sev = rs.getString("severity");
                if (sev != null) {
                    try { f.setSeverity(Severity.valueOf(sev)); } catch (Exception ignored) {}
                }
                f.setCategory    (rs.getString("category"));
                f.setMessage     (rs.getString("message"));
                f.setCodeSnippet (rs.getString("code_snippet"));
                f.setSource      (ScannerType.SEMGREP);

                Object[] row = new Object[]{f, verdict};
                if ("TP".equals(verdict)) tps.add(row);
                else fps.add(row);
            }
        }

        // Randomly sample FPs if limit set
        if (fps.size() > maxFp) {
            Collections.shuffle(fps, new Random(42));
            fps = fps.subList(0, maxFp);
        }

        System.out.printf("Subset: %d TPs + %d FPs = %d total%n",
            tps.size(), fps.size(), tps.size() + fps.size());

        List<Object[]> combined = new ArrayList<>(tps);
        combined.addAll(fps);
        return combined;
    }

    /**
     * Reads a focused context window from the source file:
     * - File header (first HEADER_LINES lines: imports, class defs)
     * - CONTEXT_LINES_BEFORE lines above the flagged line
     * - The flagged line itself (marked)
     * - CONTEXT_LINES_AFTER lines below the flagged line
     * This keeps the prompt small enough to avoid proxy timeouts while
     * providing meaningful framework and access-control context.
     */
    private static String readFile(String reposBase, String repoFolder,
                                   String filePath, Integer lineNumber) {
        if (filePath == null) return null;

        String repoName    = Paths.get(repoFolder).getFileName().toString();
        String relativePath = extractRelativePath(filePath, repoName);

        Path full = Paths.get(repoFolder, relativePath);
        if (!Files.exists(full)) return null;

        try {
            String[] lines = Files.readString(full).split("\n", -1);
            int total = lines.length;

            StringBuilder sb = new StringBuilder();

            // Header section
            int headerEnd = Math.min(HEADER_LINES, total);
            sb.append("--- FILE HEADER (lines 1-").append(headerEnd).append(") ---\n");
            for (int i = 0; i < headerEnd; i++) {
                sb.append(String.format("%4d: %s%n", i + 1, lines[i]));
            }

            // Context window around flagged line
            if (lineNumber != null && lineNumber > 0) {
                int flagged = lineNumber - 1; // 0-indexed
                int start   = Math.max(headerEnd, flagged - CONTEXT_LINES_BEFORE);
                int end     = Math.min(total, flagged + CONTEXT_LINES_AFTER + 1);

                if (start > headerEnd) sb.append("    ... (").append(start - headerEnd).append(" lines omitted) ...\n");
                sb.append("--- CONTEXT AROUND LINE ").append(lineNumber).append(" ---\n");
                for (int i = start; i < end; i++) {
                    String marker = (i == flagged) ? " <<< FLAGGED" : "";
                    sb.append(String.format("%4d: %s%s%n", i + 1, lines[i], marker));
                }
            } else {
                // No line number — include up to 200 lines after header
                int end = Math.min(total, headerEnd + 200);
                for (int i = headerEnd; i < end; i++) {
                    sb.append(String.format("%4d: %s%n", i + 1, lines[i]));
                }
            }

            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** Strips any absolute prefix from filePath, returning the part after the repo name. */
    private static String extractRelativePath(String filePath, String repoName) {
        String normalised = filePath.replace('\\', '/');
        String searchFor  = "/" + repoName + "/";
        int idx = normalised.indexOf(searchFor);
        if (idx >= 0) return normalised.substring(idx + searchFor.length());
        return filePath;
    }

    private static long createEvaluationRun(Connection conn, String name, String description)
            throws SQLException {
        String sql = "INSERT INTO evaluation_runs (name, description, created_at) VALUES (?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("Failed to create evaluation run");
    }

    private static void saveLlmResult(Connection conn, LlmResult r) throws SQLException {
        String sql = """
            INSERT INTO llm_results
              (finding_id, evaluation_run_id, llm_verdict, confidence,
               reasoning, remediation, model_used, prompt_version, created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong  (1, r.getFindingId());
            ps.setLong  (2, r.getEvaluationRunId());
            ps.setString(3, r.getLlmVerdict().name());
            ps.setInt   (4, r.getConfidence());
            ps.setString(5, r.getReasoning());
            ps.setString(6, r.getRemediation());
            ps.setString(7, r.getModelUsed());
            ps.setString(8, r.getPromptVersion());
            ps.setString(9, LocalDateTime.now().toString());
            ps.executeUpdate();
        }
    }

    private static void printMetrics(Connection conn, long evalRunId) throws SQLException {
        String sql = """
            SELECT mr.verdict as human_v, lr.llm_verdict as llm_v, COUNT(*) as cnt
            FROM llm_results lr
            JOIN manual_reviews mr ON lr.finding_id = mr.finding_id
            WHERE lr.evaluation_run_id = ?
            GROUP BY mr.verdict, lr.llm_verdict
            ORDER BY mr.verdict, lr.llm_verdict
            """;
        int tpTp = 0, tpTotal = 0, fpFp = 0, fpTotal = 0, predTp = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, evalRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String h   = rs.getString("human_v");
                    String l   = rs.getString("llm_v");
                    int    cnt = rs.getInt("cnt");
                    if ("TP".equals(h)) { tpTotal += cnt; if ("TP".equals(l)) tpTp = cnt; }
                    if ("FP".equals(h)) { fpTotal += cnt; if ("FP".equals(l)) fpFp = cnt; }
                    if ("TP".equals(l)) predTp += cnt;
                }
            }
        }
        System.out.println("\n=== v2.0 METRICS ===");
        if (tpTotal > 0) System.out.printf("TP Recall    : %.2f%%  (%d/%d)%n",
            100.0 * tpTp / tpTotal, tpTp, tpTotal);
        if (fpTotal > 0) System.out.printf("FP Agreement : %.2f%%  (%d/%d)%n",
            100.0 * fpFp / fpTotal, fpFp, fpTotal);
        if (predTp > 0)  System.out.printf("TP Precision : %.2f%%  (%d/%d predicted TP)%n",
            100.0 * tpTp / predTp, tpTp, predTp);
        System.out.println("\nCompare with v1.0: TP Recall=52.38%, FP Agreement=26.20%, TP Precision=6.76%");
    }
}
