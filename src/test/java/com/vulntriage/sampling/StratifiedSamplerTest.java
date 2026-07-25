package com.vulntriage.sampling;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class StratifiedSamplerTest {

    private StratifiedSampler sampler;

    @BeforeEach
    void setup() {
        sampler = new StratifiedSampler(42L); // fixed seed for reproducibility
    }

    // ── Helper to build test findings ─────────────────────────────────────

    private Finding finding(long id, String category, Severity severity) {
        Finding f = new Finding();
        f.setId      (id);
        f.setCategory(category);
        f.setSeverity(severity);
        f.setRuleId  ("rule-" + id);
        f.setFilePath("file-" + id + ".py");
        f.setLineNumber((int) id);
        f.setFingerprint("fp-" + id);
        return f;
    }

    private List<Finding> buildDataset() {
        List<Finding> findings = new ArrayList<>();
        // 40 XSS/WARNING
        for (int i = 1; i <= 40; i++)  findings.add(finding(i,    "xss",           Severity.WARNING));
        // 5 SQL/ERROR
        for (int i = 41; i <= 45; i++) findings.add(finding(i,    "sql_injection",  Severity.ERROR));
        // 5 CSRF/INFO (rare category)
        for (int i = 46; i <= 50; i++) findings.add(finding(i,    "csrf",           Severity.INFO));
        return findings;
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void sample_returnsExactTargetSize() {
        List<Finding> dataset = buildDataset(); // 50 findings
        List<Finding> sample  = sampler.sample(dataset, 20);
        assertEquals(20, sample.size());
    }

    @Test
    void sample_returnsAllWhenDatasetSmallerThanTarget() {
        List<Finding> dataset = buildDataset(); // 50 findings
        List<Finding> sample  = sampler.sample(dataset, 200);
        assertEquals(50, sample.size());
    }

    @Test
    void sample_rareCategory_isRepresentedInSample() {
        List<Finding> dataset = buildDataset();
        // CSRF has 5/50 = 10% of findings, target = 20 → expect at least 1 CSRF
        List<Finding> sample  = sampler.sample(dataset, 20);

        long csrfCount = sample.stream()
            .filter(f -> "csrf".equals(f.getCategory()))
            .count();

        assertTrue(csrfCount >= 1, "Rare CSRF category should be in sample");
    }

    @Test
    void sample_noDuplicates() {
        List<Finding> dataset = buildDataset();
        List<Finding> sample  = sampler.sample(dataset, 30);

        long distinctIds = sample.stream().map(Finding::getId).distinct().count();
        assertEquals(sample.size(), distinctIds, "Sample should have no duplicates");
    }

    @Test
    void sample_emptyInput_returnsEmpty() {
        assertTrue(sampler.sample(List.of(), 10).isEmpty());
    }

    @Test
    void sample_zeroTarget_returnsEmpty() {
        assertTrue(sampler.sample(buildDataset(), 0).isEmpty());
    }

    @Test
    void sample_allCategoriesRepresented_forLargeSample() {
        List<Finding> dataset = buildDataset();
        List<Finding> sample  = sampler.sample(dataset, 45);

        Map<String, Long> categoryCounts = sample.stream()
            .collect(Collectors.groupingBy(Finding::getCategory, Collectors.counting()));

        assertTrue(categoryCounts.containsKey("xss"),          "XSS should be in sample");
        assertTrue(categoryCounts.containsKey("sql_injection"), "SQL should be in sample");
        assertTrue(categoryCounts.containsKey("csrf"),          "CSRF should be in sample");
    }
}
