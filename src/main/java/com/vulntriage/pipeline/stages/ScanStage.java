package com.vulntriage.pipeline.stages;

import com.vulntriage.domain.ScanRun;
import com.vulntriage.domain.enums.PipelineStatus;
import com.vulntriage.domain.enums.ScannerType;
import com.vulntriage.event.PipelineObserver;
import com.vulntriage.normaliser.FindingNormaliser;
import com.vulntriage.pipeline.api.AbstractPipelineStage;
import com.vulntriage.pipeline.api.PipelineContext;
import com.vulntriage.repository.api.FindingRepository;
import com.vulntriage.repository.api.ScanRunRepository;
import com.vulntriage.scanner.api.RawFinding;
import com.vulntriage.scanner.api.ScannerAdapter;
import com.vulntriage.scanner.factory.ScannerFactory;
import com.vulntriage.domain.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1: Run the configured scanners against the repository.
 *
 * Uses ScannerFactory (Factory pattern) to get the right adapters.
 * Normalises raw findings and saves them to the database.
 * Sets ctx.allFindings for the next stage.
 */
public class ScanStage extends AbstractPipelineStage {

    private static final Logger log = LoggerFactory.getLogger(ScanStage.class);

    private final ScanRunRepository  scanRunRepo;
    private final FindingRepository  findingRepo;
    private final FindingNormaliser  normaliser;
    private final com.vulntriage.config.ScanConfig configOverride;

    public ScanStage(List<PipelineObserver> observers,
                     ScanRunRepository scanRunRepo,
                     FindingRepository findingRepo) {
        this(observers, scanRunRepo, findingRepo, null);
    }

    public ScanStage(List<PipelineObserver> observers,
                     ScanRunRepository scanRunRepo,
                     FindingRepository findingRepo,
                     com.vulntriage.config.ScanConfig configOverride) {
        super(observers);
        this.scanRunRepo    = scanRunRepo;
        this.findingRepo    = findingRepo;
        this.normaliser     = new FindingNormaliser();
        this.configOverride = configOverride;
    }

    @Override
    public String getName() { return "Scan"; }

    @Override
    protected int progressAfter() { return 30; }

    @Override
    protected void doExecute(PipelineContext ctx) {
        String repoPath     = ctx.getRepository().getLocalPath();
        long   repositoryId = ctx.getRepository().getId();
        List<Finding> found = new ArrayList<>();

        com.vulntriage.config.ScanConfig cfg =
            (configOverride != null) ? configOverride : ctx.getConfig();

        if (cfg.isRunSemgrep()) {
            found.addAll(runScanner(ScannerType.SEMGREP, repoPath, repositoryId, cfg, ctx));
        }
        if (cfg.isRunTrivy()) {
            found.addAll(runScanner(ScannerType.TRIVY, repoPath, repositoryId, cfg, ctx));
        }
        if (cfg.isRunGitleaks()) {
            found.addAll(runScanner(ScannerType.GITLEAKS, repoPath, repositoryId, cfg, ctx));
        }
        if (cfg.isRunCodeql()) {
            found.addAll(runScanner(ScannerType.CODEQL, repoPath, repositoryId, cfg, ctx));
        }
        if (cfg.isRunSonarqube()) {
            found.addAll(runSonarQube(repoPath, repositoryId, cfg, ctx));
        }

        log.info("Scan stage complete: {} findings (this step)", found.size());
        ctx.getAllFindings().addAll(found);
    }

    private List<Finding> runScanner(ScannerType type,
                                     String repoPath,
                                     long repositoryId,
                                     com.vulntriage.config.ScanConfig cfg,
                                     PipelineContext ctx) {
        ScannerAdapter adapter = ScannerFactory.create(type);

        if (!adapter.isAvailable()) {
            log.warn("{} not available on PATH — skipping", type);
            return List.of();
        }

        // Create and persist a scan run record
        ScanRun run = new ScanRun(repositoryId, type);
        scanRunRepo.save(run);

        try {
            List<RawFinding> raw = adapter.scan(repoPath, cfg);
            List<Finding> findings = normaliser.normaliseAll(raw, repositoryId, run.getId());

            // Save to DB (dedup via INSERT OR IGNORE on fingerprint)
            findings.forEach(findingRepo::save);

            // Update scan run status
            run.setStatus(PipelineStatus.COMPLETE);
            run.setCompletedAt(LocalDateTime.now());
            run.setFindingsCount(findings.size());
            scanRunRepo.update(run);

            log.info("{} scan: {} findings saved", type, findings.size());
            return findings;

        } catch (Exception e) {
            run.setStatus(PipelineStatus.FAILED);
            run.setCompletedAt(LocalDateTime.now());
            scanRunRepo.update(run);
            log.error("{} scan failed: {}", type, e.getMessage());
            throw e;
        }
    }

    private List<Finding> runSonarQube(String repoPath, long repositoryId,
                                        com.vulntriage.config.ScanConfig cfg,
                                        PipelineContext ctx) {
        if (cfg.getSonarUrl().isBlank() || cfg.getSonarToken().isBlank()) {
            log.warn("SonarQube skipped — sonar_url and sonar_token are required");
            return List.of();
        }
        com.vulntriage.scanner.sonarqube.SonarQubeAdapter adapter =
            new com.vulntriage.scanner.sonarqube.SonarQubeAdapter(cfg.getSonarUrl(), cfg.getSonarToken());

        ScanRun run = new ScanRun(repositoryId, ScannerType.SONARQUBE);
        scanRunRepo.save(run);
        try {
            List<RawFinding> raw = adapter.scan(repoPath, cfg);
            List<Finding> findings = normaliser.normaliseAll(raw, repositoryId, run.getId());
            findings.forEach(findingRepo::save);
            run.setStatus(PipelineStatus.COMPLETE);
            run.setCompletedAt(LocalDateTime.now());
            run.setFindingsCount(findings.size());
            scanRunRepo.update(run);
            log.info("SONARQUBE scan: {} findings saved", findings.size());
            return findings;
        } catch (Exception e) {
            run.setStatus(PipelineStatus.FAILED);
            run.setCompletedAt(LocalDateTime.now());
            scanRunRepo.update(run);
            log.error("SONARQUBE scan failed: {}", e.getMessage());
            throw e;
        }
    }
}
