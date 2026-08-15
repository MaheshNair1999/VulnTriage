package com.vulntriage.pipeline.stages;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.enums.Severity;
import com.vulntriage.event.PipelineObserver;
import com.vulntriage.pipeline.api.AbstractPipelineStage;
import com.vulntriage.pipeline.api.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pipeline stage that selects findings from the database using filter criteria.
 *
 * Unlike SCAN (which runs a scanner) or FILTER (which filters scan output),
 * SELECT loads existing DB findings directly and populates ctx.sample.
 * This enables targeted re-triage workflows that operate on a chosen subset
 * without needing a fresh scan.
 *
 * Params:
 *   scanner      — "all" or scanner name (SEMGREP, TRIVY, …)
 *   repository   — "all" or exact repository name
 *   rule_pattern — substring match against ruleId (case-insensitive); empty = all
 *   severity     — "all" or comma-separated list: ERROR, WARNING, INFO
 */
public class SelectStage extends AbstractPipelineStage {

    private static final Logger log = LoggerFactory.getLogger(SelectStage.class);

    private final String scanner;
    private final String repositoryName;
    private final String rulePattern;
    private final List<String> severities;

    public SelectStage(List<PipelineObserver> observers,
                       String scanner,
                       String repositoryName,
                       String rulePattern,
                       String severity) {
        super(observers);
        this.scanner        = scanner      != null ? scanner.trim()        : "all";
        this.repositoryName = repositoryName != null ? repositoryName.trim() : "all";
        this.rulePattern    = rulePattern  != null ? rulePattern.trim()    : "";

        // Parse comma-separated severities into uppercase list
        String sev = severity != null ? severity.trim() : "all";
        if (sev.isBlank() || "all".equalsIgnoreCase(sev)) {
            this.severities = List.of();
        } else {
            this.severities = List.of(sev.toUpperCase().split(",")).stream()
                .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toList());
        }
    }

    @Override
    public String getName() { return "Select"; }

    @Override
    protected int progressAfter() { return 20; }

    @Override
    protected void doExecute(PipelineContext ctx) {
        AppContext app = AppContext.getInstance();

        // Build repoId → repoName map and collect all findings
        Map<Long, String> repoNameById = new HashMap<>();
        List<Finding> allFindings = new ArrayList<>();

        app.repositoryRepo().findAll().forEach(r -> {
            repoNameById.put(r.getId(), r.getName() != null ? r.getName() : "");
            allFindings.addAll(app.findingRepo().findByRepositoryId(r.getId()));
        });

        List<Finding> filtered = allFindings.stream().filter(f -> {
            // Scanner filter
            if (!"all".equalsIgnoreCase(scanner) && !scanner.isBlank()) {
                if (f.getSource() == null
                        || !f.getSource().name().equalsIgnoreCase(scanner)) return false;
            }
            // Repository filter
            if (!"all".equalsIgnoreCase(repositoryName) && !repositoryName.isBlank()) {
                String rname = repoNameById.getOrDefault(f.getRepositoryId(), "");
                if (!repositoryName.equalsIgnoreCase(rname)) return false;
            }
            // Rule pattern filter (substring, case-insensitive)
            if (!rulePattern.isBlank()) {
                if (f.getRuleId() == null
                        || !f.getRuleId().toLowerCase().contains(rulePattern.toLowerCase())) return false;
            }
            // Severity filter
            if (!severities.isEmpty()) {
                if (f.getSeverity() == null) return false;
                if (!severities.contains(f.getSeverity().name())) return false;
            }
            return true;
        }).collect(Collectors.toList());

        log.info("SelectStage: {} / {} findings matched (scanner={}, repo={}, rule='{}', severity={})",
            filtered.size(), allFindings.size(), scanner, repositoryName, rulePattern, severities);

        ctx.setAllFindings(allFindings);
        ctx.setSample(filtered);
    }
}
