package com.vulntriage.domain;

import com.vulntriage.domain.enums.PipelineStatus;
import com.vulntriage.domain.enums.ScannerType;
import java.time.LocalDateTime;

/**
 * Records one execution of a scanner against a repository.
 * All findings produced in a scan are linked back to their ScanRun.
 */
public class ScanRun {

    private long           id;
    private long           repositoryId;
    private ScannerType    scannerType;
    private LocalDateTime  startedAt;
    private LocalDateTime  completedAt;  // null while running
    private PipelineStatus status;
    private int            findingsCount;

    public ScanRun() {}

    public ScanRun(long repositoryId, ScannerType scannerType) {
        this.repositoryId = repositoryId;
        this.scannerType  = scannerType;
        this.startedAt    = LocalDateTime.now();
        this.status       = PipelineStatus.RUNNING;
        this.findingsCount = 0;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public long getId()                             { return id; }
    public void setId(long id)                      { this.id = id; }

    public long getRepositoryId()                   { return repositoryId; }
    public void setRepositoryId(long repositoryId)  { this.repositoryId = repositoryId; }

    public ScannerType getScannerType()             { return scannerType; }
    public void setScannerType(ScannerType t)       { this.scannerType = t; }

    public LocalDateTime getStartedAt()             { return startedAt; }
    public void setStartedAt(LocalDateTime t)       { this.startedAt = t; }

    public LocalDateTime getCompletedAt()           { return completedAt; }
    public void setCompletedAt(LocalDateTime t)     { this.completedAt = t; }

    public PipelineStatus getStatus()               { return status; }
    public void setStatus(PipelineStatus status)    { this.status = status; }

    public int getFindingsCount()                   { return findingsCount; }
    public void setFindingsCount(int n)             { this.findingsCount = n; }
}
