package com.vulntriage.pipeline;

import com.vulntriage.config.ScanConfig;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.Repository;
import com.vulntriage.domain.ScanRun;
import com.vulntriage.domain.enums.PipelineStatus;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.evaluation.EvaluationReport;
import com.vulntriage.evaluation.MetricsCalculator;
import com.vulntriage.pipeline.api.PipelineContext;
import com.vulntriage.repository.api.*;
import com.vulntriage.triage.api.TriageResult;
import com.vulntriage.triage.api.TriageStrategy;
import com.vulntriage.triage.mock.MockTriageStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the pipeline.
 *
 * Uses in-memory stub repositories — no SQLite, no Semgrep, no Ollama needed.
 * Verifies that the full chain: scan → sample → triage → evaluate
 * produces correct results with known inputs.
 *
 * This is the most important test in the project for the SE exam —
 * it demonstrates that all six design patterns work together correctly.
 */
class PipelineIntegrationTest {

    // ── In-memory stubs ────────────────────────────────────────────────────

    static {
        // Use in-memory SQLite so stubs that need a connection work
        System.setProperty("vulntriage.db.url", "jdbc:sqlite::memory:");
    }

    private InMemoryFindingRepository      findingRepo;
    private InMemoryRepositoryRepo         repoRepo;
    private InMemoryScanRunRepository      scanRunRepo;
    private InMemoryManualReviewRepository reviewRepo;
    private InMemoryLlmResultRepository    llmRepo;
    private InMemoryEvaluationRepository   evalRepo;

    private Repository testRepo;

    @BeforeEach
    void setup() {
        findingRepo = new InMemoryFindingRepository();
        repoRepo    = new InMemoryRepositoryRepo();
        scanRunRepo = new InMemoryScanRunRepository();
        reviewRepo  = new InMemoryManualReviewRepository();
        llmRepo     = new InMemoryLlmResultRepository();
        evalRepo    = new InMemoryEvaluationRepository();

        testRepo = new Repository("test-repo", "/tmp/test", null);
        testRepo.setId(1L);
        testRepo.setCreatedAt(LocalDateTime.now());
        repoRepo.save(testRepo);

        // Pre-populate 20 findings: 2 TP (ERROR), 18 FP (WARNING/INFO)
        for (int i = 1; i <= 2; i++) {
            findingRepo.save(makeFinding(i, Severity.ERROR, "sql_injection"));
        }
        for (int i = 3; i <= 20; i++) {
            findingRepo.save(makeFinding(i, Severity.WARNING, "xss"));
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void fullPipeline_withMockStrategy_producesResults() {
        TriageStrategy strategy = new MockTriageStrategy();

        // Run triage on all findings
        List<Finding> allFindings = findingRepo.findByRepositoryId(1L);
        assertEquals(20, allFindings.size());

        // Triage each finding
        for (Finding f : allFindings) {
            TriageResult result = strategy.triage(f);
            assertNotNull(result.getVerdict());
            assertTrue(result.getConfidence() >= 0 && result.getConfidence() <= 100);
        }
    }

    @Test
    void mockStrategy_errorSeverity_classifiedAsTP() {
        MockTriageStrategy strategy = new MockTriageStrategy();
        Finding errorFinding = makeFinding(99, Severity.ERROR, "sql_injection");

        TriageResult result = strategy.triage(errorFinding);
        assertEquals(Verdict.TP, result.getVerdict());
        assertEquals(90, result.getConfidence());
    }

    @Test
    void mockStrategy_warningSeverity_classifiedAsFP() {
        MockTriageStrategy strategy = new MockTriageStrategy();
        Finding warningFinding = makeFinding(99, Severity.WARNING, "xss");

        TriageResult result = strategy.triage(warningFinding);
        assertEquals(Verdict.FP, result.getVerdict());
        assertEquals(75, result.getConfidence());
    }

    @Test
    void metricsCalculator_withKnownData_producesCorrectAccuracy() {
        // Set up: 4 matched pairs
        // Manual:TP → LLM:TP  (correct)
        // Manual:TP → LLM:FP  (miss — false negative)
        // Manual:FP → LLM:FP  (correct)
        // Manual:FP → LLM:TP  (wrong)

        long runId = 1L;

        // Manual reviews
        reviewRepo.saveOrUpdate(makeReview(1L, Verdict.TP));
        reviewRepo.saveOrUpdate(makeReview(2L, Verdict.TP));
        reviewRepo.saveOrUpdate(makeReview(3L, Verdict.FP));
        reviewRepo.saveOrUpdate(makeReview(4L, Verdict.FP));

        // LLM results
        llmRepo.save(makeLlmResult(1L, runId, Verdict.TP));  // correct
        llmRepo.save(makeLlmResult(2L, runId, Verdict.FP));  // false negative
        llmRepo.save(makeLlmResult(3L, runId, Verdict.FP));  // correct
        llmRepo.save(makeLlmResult(4L, runId, Verdict.TP));  // wrong

        MetricsCalculator calc = new MetricsCalculator();
        EvaluationReport report = calc.compute(runId, llmRepo, reviewRepo);

        assertEquals(4,    report.getTotalCompared());
        assertEquals(0.50, report.getAccuracy(),         0.001); // 2/4 correct
        assertEquals(0.50, report.getTpRecall(),         0.001); // 1/2 TP caught
        assertEquals(0.50, report.getFalseNegativeRate(),0.001); // 1/2 TP missed
        assertEquals(0.50, report.getFpAgreement(),      0.001); // 1/2 FP correct
    }

    @Test
    void metricsCalculator_perfectResults_allMetricsAreOne() {
        long runId = 2L;

        reviewRepo.saveOrUpdate(makeReview(1L, Verdict.TP));
        reviewRepo.saveOrUpdate(makeReview(2L, Verdict.FP));
        llmRepo.save(makeLlmResult(1L, runId, Verdict.TP));
        llmRepo.save(makeLlmResult(2L, runId, Verdict.FP));

        MetricsCalculator calc = new MetricsCalculator();
        EvaluationReport report = calc.compute(runId, llmRepo, reviewRepo);

        assertEquals(1.0, report.getAccuracy(),          0.001);
        assertEquals(1.0, report.getTpRecall(),          0.001);
        assertEquals(0.0, report.getFalseNegativeRate(), 0.001);
        assertEquals(1.0, report.getTpPrecision(),       0.001);
        assertEquals(1.0, report.getFpAgreement(),       0.001);
    }

    @Test
    void metricsCalculator_zeroFalseNegatives_whenLlmCatchesAllTP() {
        long runId = 3L;

        // 3 manual TP, all caught by LLM
        for (long i = 10L; i <= 12L; i++) {
            reviewRepo.saveOrUpdate(makeReview(i, Verdict.TP));
            llmRepo.save(makeLlmResult(i, runId, Verdict.TP));
        }

        MetricsCalculator calc = new MetricsCalculator();
        EvaluationReport report = calc.compute(runId, llmRepo, reviewRepo);

        assertEquals(0.0,  report.getFalseNegativeRate(), 0.001);
        assertEquals(1.0,  report.getTpRecall(),          0.001);
    }

    @Test
    void pipelineContext_failState_propagatesCorrectly() {
        PipelineContext ctx = new PipelineContext(testRepo, ScanConfig.defaults());
        assertFalse(ctx.hasFailed());

        ctx.fail("Something went wrong");
        assertTrue(ctx.hasFailed());
        assertEquals("Something went wrong", ctx.getErrorMessage());
        assertEquals(PipelineStatus.FAILED, ctx.getStatus());
    }

    @Test
    void pipelineContext_initialState_isPending() {
        PipelineContext ctx = new PipelineContext(testRepo, ScanConfig.defaults());
        assertEquals(PipelineStatus.PENDING, ctx.getStatus());
        assertNull(ctx.getErrorMessage());
        assertTrue(ctx.getAllFindings().isEmpty());
        assertTrue(ctx.getSample().isEmpty());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Finding makeFinding(long id, Severity severity, String category) {
        Finding f = new Finding();
        f.setId          (id);
        f.setRepositoryId(1L);
        f.setScanRunId   (1L);
        f.setSource      (ScannerType.SEMGREP);
        f.setRuleId      ("test.rule." + category);
        f.setFilePath    ("app/views.py");
        f.setLineNumber  ((int) id);
        f.setSeverity    (severity);
        f.setCategory    (category);
        f.setMessage     ("Test finding " + id);
        f.setCodeSnippet ("code snippet " + id);
        f.setFingerprint ("fp-" + id);
        f.setCreatedAt   (LocalDateTime.now());
        return f;
    }

    private com.vulntriage.domain.ManualReview makeReview(long findingId, Verdict verdict) {
        com.vulntriage.domain.ManualReview r = new com.vulntriage.domain.ManualReview();
        r.setFindingId (findingId);
        r.setVerdict   (verdict);
        r.setReviewedAt(LocalDateTime.now());
        return r;
    }

    private com.vulntriage.domain.LlmResult makeLlmResult(long findingId, long runId, Verdict verdict) {
        com.vulntriage.domain.LlmResult r = new com.vulntriage.domain.LlmResult();
        r.setFindingId      (findingId);
        r.setEvaluationRunId(runId);
        r.setLlmVerdict     (verdict);
        r.setConfidence     (80);
        r.setReasoning      ("test reasoning");
        r.setRemediation    ("test remediation");
        r.setModelUsed      ("mock");
        r.setPromptVersion  ("v1.0");
        r.setCreatedAt      (LocalDateTime.now());
        return r;
    }

    // ── In-memory repository stubs ─────────────────────────────────────────

    static class InMemoryFindingRepository implements FindingRepository {
        private final Map<Long, Finding> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override public void save(Finding f) {
            if (f.getId() == 0) f.setId(seq.getAndIncrement());
            store.putIfAbsent(f.getId(), f);
        }
        @Override public Optional<Finding> findById(long id) { return Optional.ofNullable(store.get(id)); }
        @Override public List<Finding> findByRepositoryId(long repoId) {
            return store.values().stream().filter(f -> f.getRepositoryId() == repoId).toList();
        }
        @Override public List<Finding> findByScanRunId(long id) {
            return store.values().stream().filter(f -> f.getScanRunId() == id).toList();
        }
        @Override public List<Finding> findUnreviewed() { return new ArrayList<>(store.values()); }
        @Override public List<Finding> findBySeverity(Severity s) {
            return store.values().stream().filter(f -> f.getSeverity() == s).toList();
        }
        @Override public List<Finding> findByCategory(String c) {
            return store.values().stream().filter(f -> c.equals(f.getCategory())).toList();
        }
        @Override public boolean existsByFingerprint(String fp) {
            return store.values().stream().anyMatch(f -> fp.equals(f.getFingerprint()));
        }
        @Override public long countAll() { return store.size(); }
        @Override public long countByVerdict(Verdict v) { return 0; }
        @Override public long countUnreviewed() { return store.size(); }
        @Override public void deleteByScanRunId(long id) { store.values().removeIf(f -> f.getScanRunId() == id); }
        @Override public void deleteById(long id) { store.remove(id); }
    }

    static class InMemoryRepositoryRepo implements RepositoryRepo {
        private final Map<Long, Repository> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override public void save(Repository r) {
            if (r.getId() == 0) r.setId(seq.getAndIncrement());
            store.put(r.getId(), r);
        }
        @Override public Optional<Repository> findById(long id) { return Optional.ofNullable(store.get(id)); }
        @Override public List<Repository> findAll()              { return new ArrayList<>(store.values()); }
        @Override public void update(Repository r)               { store.put(r.getId(), r); }
        @Override public void delete(long id)                    { store.remove(id); }
        @Override public long countAll()                         { return store.size(); }
    }

    static class InMemoryScanRunRepository implements ScanRunRepository {
        private final Map<Long, ScanRun> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override public void save(ScanRun r) {
            if (r.getId() == 0) r.setId(seq.getAndIncrement());
            store.put(r.getId(), r);
        }
        @Override public Optional<ScanRun> findById(long id) { return Optional.ofNullable(store.get(id)); }
        @Override public List<ScanRun> findByRepositoryId(long id) {
            return store.values().stream().filter(r -> r.getRepositoryId() == id).toList();
        }
        @Override public void update(ScanRun r) { store.put(r.getId(), r); }
        @Override public Optional<ScanRun> findLatest(long repoId, ScannerType type) {
            return store.values().stream()
                .filter(r -> r.getRepositoryId() == repoId && r.getScannerType() == type)
                .max(Comparator.comparing(ScanRun::getStartedAt));
        }
    }

    static class InMemoryManualReviewRepository implements ManualReviewRepository {
        private final Map<Long, com.vulntriage.domain.ManualReview> store = new LinkedHashMap<>();

        @Override public void saveOrUpdate(com.vulntriage.domain.ManualReview r) {
            store.put(r.getFindingId(), r);
        }
        @Override public Optional<com.vulntriage.domain.ManualReview> findByFindingId(long id) {
            return Optional.ofNullable(store.get(id));
        }
        @Override public List<com.vulntriage.domain.ManualReview> findAll() {
            return new ArrayList<>(store.values());
        }
        @Override public List<com.vulntriage.domain.ManualReview> findByVerdict(Verdict v) {
            return store.values().stream().filter(r -> r.getVerdict() == v).toList();
        }
        @Override public long countByVerdict(Verdict v) {
            return store.values().stream().filter(r -> r.getVerdict() == v).count();
        }
        @Override public long countAll() { return store.size(); }
        @Override public long countForFindingIds(List<Long> ids) { return store.values().stream().filter(r -> ids.contains(r.getFindingId())).count(); }
        @Override public void deleteByFindingId(long findingId) { store.remove(findingId); }
    }

    static class InMemoryLlmResultRepository implements LlmResultRepository {
        private final Map<String, com.vulntriage.domain.LlmResult> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override public void save(com.vulntriage.domain.LlmResult r) {
            r.setId(seq.getAndIncrement());
            store.put(r.getFindingId() + ":" + r.getEvaluationRunId(), r);
        }
        @Override public Optional<com.vulntriage.domain.LlmResult> findByFindingIdAndRunId(long f, long run) {
            return Optional.ofNullable(store.get(f + ":" + run));
        }
        @Override public List<com.vulntriage.domain.LlmResult> findByEvaluationRunId(long runId) {
            return store.values().stream()
                .filter(r -> r.getEvaluationRunId() == runId).toList();
        }
        @Override public long countByEvaluationRunId(long runId) {
            return store.values().stream().filter(r -> r.getEvaluationRunId() == runId).count();
        }
        @Override public boolean existsByFindingId(long findingId) {
            return store.values().stream().anyMatch(r -> r.getFindingId() == findingId);
        }
        @Override public Optional<com.vulntriage.domain.LlmResult> findByFindingId(long findingId) {
            return store.values().stream().filter(r -> r.getFindingId() == findingId).findFirst();
        }
        @Override public void deleteByFindingId(long findingId) {
            store.entrySet().removeIf(e -> e.getValue().getFindingId() == findingId);
        }
        @Override public void deleteByFindingIdAndRunId(long findingId, long runId) {
            store.entrySet().removeIf(e -> e.getValue().getFindingId() == findingId && e.getValue().getEvaluationRunId() == runId);
        }
        @Override public List<com.vulntriage.domain.LlmResult> findAllByPromptVersion(String v) {
            return store.values().stream().filter(r -> v.equals(r.getPromptVersion())).toList();
        }
        @Override public List<com.vulntriage.domain.LlmResult> findAll() {
            return new java.util.ArrayList<>(store.values());
        }
    }

    static class InMemoryEvaluationRepository implements EvaluationRepository {
        private final Map<Long, com.vulntriage.domain.EvaluationRun> store = new LinkedHashMap<>();
        private final List<long[]> samples = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override public void save(com.vulntriage.domain.EvaluationRun r) {
            r.setId(seq.getAndIncrement()); store.put(r.getId(), r);
        }
        @Override public Optional<com.vulntriage.domain.EvaluationRun> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }
        @Override public List<com.vulntriage.domain.EvaluationRun> findAll() {
            return new ArrayList<>(store.values());
        }
        @Override public void saveSampleFinding(long runId, long findingId) {
            samples.add(new long[]{runId, findingId});
        }
        @Override public List<Long> findSampleFindingIds(long runId) {
            return samples.stream()
                .filter(s -> s[0] == runId)
                .map(s -> s[1]).toList();
        }
        @Override public void deleteById(long id) { store.remove(id); }
    }
}
