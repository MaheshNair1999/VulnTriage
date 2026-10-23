package com.vulntriage.pipeline.stages;

import com.vulntriage.app.AppContext;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.Repository;
import com.vulntriage.event.PipelineObserver;
import com.vulntriage.pipeline.api.AbstractPipelineStage;
import com.vulntriage.pipeline.api.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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

        // Resolve repositoryName → repositoryId for DB-side filtering
        long repoId = -1L;
        if (!"all".equalsIgnoreCase(repositoryName) && !repositoryName.isBlank()) {
            for (Repository r : app.repositoryRepo().findAll()) {
                if (repositoryName.equalsIgnoreCase(r.getName())) {
                    repoId = r.getId();
                    break;
                }
            }
        }

        // Map multi-value severity list to a single string for the paginated query
        // (findPageFiltered supports one severity value; for multi-value we post-filter)
        String sevParam = (severities.size() == 1) ? severities.get(0) : null;
        String srcParam = (!"all".equalsIgnoreCase(scanner) && !scanner.isBlank()) ? scanner : null;
        String qParam   = rulePattern.isBlank() ? null : rulePattern;

        // Page through all matching findings in 5000-row batches to stay off-heap
        List<Finding> filtered = new ArrayList<>();
        long afterId = 0L;
        int page;
        do {
            List<Finding> batch = app.findingRepo().findPageFiltered(
                repoId, sevParam, srcParam, qParam, afterId, 5000);
            // Post-filter for multi-severity and remaining criteria the single SQL param can't cover
            for (Finding f : batch) {
                if (!severities.isEmpty() && (f.getSeverity() == null
                        || !severities.contains(f.getSeverity().name()))) continue;
                filtered.add(f);
            }
            page = batch.size();
            if (page > 0) afterId = batch.get(page - 1).getId();
        } while (page == 5000);

        long totalMatched = app.findingRepo().countFiltered(repoId, sevParam, srcParam, qParam);
        log.info("SelectStage: {} findings matched (scanner={}, repo={}, rule='{}', severity={}, total in scope={})",
            filtered.size(), scanner, repositoryName, rulePattern, severities, totalMatched);

        ctx.setAllFindings(filtered);
        ctx.setSample(filtered);
    }
}
