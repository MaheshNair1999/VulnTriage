package com.vulntriage.repository;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.repository.sqlite.SQLiteConnection;
import com.vulntriage.repository.sqlite.SQLiteFindingRepository;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SQLiteFindingRepository.
 *
 * Root cause of FK failures: findings references repositories(id) and
 * scan_runs(id). Tests must insert those parent rows first.
 *
 * We use a shared in-memory DB for the test class and insert one test
 * repository + scan_run in @BeforeEach so every test has valid FK targets.
 */
class FindingRepositoryTest {

    static {
        // Must be set before SQLiteConnection class is loaded by the JVM.
        // Static blocks in the test class run when the class is first loaded,
        // which is before @BeforeEach, so this is safe.
        System.setProperty("vulntriage.db.url", "jdbc:sqlite::memory:");
    }

    private SQLiteFindingRepository repo;

    // IDs of the parent rows we insert in @BeforeEach
    private static final long TEST_REPO_ID     = 1L;
    private static final long TEST_SCAN_RUN_ID = 1L;

    @BeforeEach
    void setup() throws Exception {
        repo = new SQLiteFindingRepository();
        insertPrerequisiteRows();
    }

    /**
     * Insert a test repository and scan_run so that findings can reference
     * them without violating FK constraints.
     * INSERT OR IGNORE means repeated @BeforeEach calls are idempotent.
     */
    private void insertPrerequisiteRows() throws Exception {
        Connection conn = SQLiteConnection.getInstance().getConnection();

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO repositories (id, name, local_path, created_at) " +
                "VALUES (1, 'test-repo', '/tmp/test', ?)")) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO scan_runs " +
                "(id, repository_id, scanner_type, started_at, status, findings_count) " +
                "VALUES (1, 1, 'SEMGREP', ?, 'COMPLETE', 0)")) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.executeUpdate();
        }
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private Finding buildFinding(String fingerprint) {
        Finding f = new Finding();
        f.setScanRunId   (TEST_SCAN_RUN_ID);
        f.setRepositoryId(TEST_REPO_ID);
        f.setSource      (ScannerType.SEMGREP);
        f.setRuleId      ("python.django.xss.test-rule");
        f.setFilePath    ("app/views.py");
        f.setLineNumber  (42);
        f.setSeverity    (Severity.WARNING);
        f.setCategory    ("xss");
        f.setMessage     ("Potential XSS vulnerability");
        f.setCodeSnippet ("return HttpResponse(request.GET.get('q'))");
        f.setFingerprint (fingerprint);
        return f;
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void save_assignsGeneratedId() {
        Finding f = buildFinding("fp-001");
        repo.save(f);
        assertTrue(f.getId() > 0, "ID should be assigned after save");
    }

    @Test
    void findById_returnsCorrectFields() {
        Finding saved = buildFinding("fp-002");
        repo.save(saved);

        Optional<Finding> found = repo.findById(saved.getId());

        assertTrue(found.isPresent());
        assertEquals("python.django.xss.test-rule", found.get().getRuleId());
        assertEquals(Severity.WARNING,               found.get().getSeverity());
        assertEquals(ScannerType.SEMGREP,            found.get().getSource());
        assertEquals("app/views.py",                 found.get().getFilePath());
        assertEquals(42,                             found.get().getLineNumber());
    }

    @Test
    void findById_returnsEmpty_whenNotFound() {
        assertTrue(repo.findById(99999L).isEmpty());
    }

    @Test
    void existsByFingerprint_trueForExisting_falseForMissing() {
        repo.save(buildFinding("fp-003"));

        assertTrue (repo.existsByFingerprint("fp-003"));
        assertFalse(repo.existsByFingerprint("fp-missing"));
    }

    @Test
    void save_duplicateFingerprint_isIgnoredGracefully() {
        // INSERT OR IGNORE — duplicate fingerprints are silently dropped
        assertDoesNotThrow(() -> {
            repo.save(buildFinding("fp-004"));
            repo.save(buildFinding("fp-004")); // duplicate — should not throw
        });
    }

    @Test
    void countAll_incrementsAfterSave() {
        long before = repo.countAll();
        repo.save(buildFinding("fp-005"));
        assertEquals(before + 1, repo.countAll());
    }

    @Test
    void findBySeverity_returnsOnlyMatchingSeverity() {
        Finding warning = buildFinding("fp-006-warn");
        warning.setSeverity(Severity.WARNING);
        repo.save(warning);

        Finding error = buildFinding("fp-006-err");
        error.setSeverity(Severity.ERROR);
        repo.save(error);

        var errors = repo.findBySeverity(Severity.ERROR);
        assertFalse(errors.isEmpty());
        errors.forEach(f -> assertEquals(Severity.ERROR, f.getSeverity()));
    }

    @Test
    void countUnreviewed_incrementsAfterSave() {
        long before = repo.countUnreviewed();
        repo.save(buildFinding("fp-007"));
        assertEquals(before + 1, repo.countUnreviewed());
    }

    @Test
    void findByCategory_returnsOnlyMatchingCategory() {
        Finding xss = buildFinding("fp-008-xss");
        xss.setCategory("xss");
        repo.save(xss);

        Finding sql = buildFinding("fp-008-sql");
        sql.setCategory("sql_injection");
        repo.save(sql);

        var xssFindings = repo.findByCategory("xss");
        assertFalse(xssFindings.isEmpty());
        xssFindings.forEach(f -> assertEquals("xss", f.getCategory()));
    }

    @Test
    void deleteByScanRunId_removesFindings() {
        Finding f = buildFinding("fp-009");
        repo.save(f);

        long before = repo.countAll();
        repo.deleteByScanRunId(TEST_SCAN_RUN_ID);
        assertTrue(repo.countAll() < before);
    }
}
