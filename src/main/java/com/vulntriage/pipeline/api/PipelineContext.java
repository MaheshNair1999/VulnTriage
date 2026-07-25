package com.vulntriage.pipeline.api;

import com.vulntriage.config.ScanConfig;
import com.vulntriage.domain.EvaluationRun;
import com.vulntriage.domain.Finding;
import com.vulntriage.domain.Repository;
import com.vulntriage.domain.enums.PipelineStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared state object passed through every stage in the pipeline chain.
 *
 * Each stage reads from and writes to this context. Stages should not
 * communicate with each other in any other way — all shared state lives here.
 *
 * If a stage encounters an unrecoverable error, it sets status to FAILED
 * and sets errorMessage, then returns without calling the next stage.
 */
public class PipelineContext {

    // ── Inputs (set before pipeline starts) ───────────────────────────────
    private Repository     repository;
    private ScanConfig     config;

    // ── State built up by stages ──────────────────────────────────────────
    private List<Finding>  allFindings  = new ArrayList<>();  // after ScanStage + NormaliseStage
    private List<Finding>  sample       = new ArrayList<>();  // after SampleStage
    private EvaluationRun  evaluationRun;                     // after TriageStage

    // ── Execution metadata ────────────────────────────────────────────────
    private PipelineStatus status       = PipelineStatus.PENDING;
    private String         errorMessage;

    // ── Constructor ────────────────────────────────────────────────────────

    public PipelineContext(Repository repository, ScanConfig config) {
        this.repository = repository;
        this.config     = config;
    }

    // ── Convenience methods ────────────────────────────────────────────────

    public boolean hasFailed() {
        return status == PipelineStatus.FAILED;
    }

    public void fail(String reason) {
        this.status       = PipelineStatus.FAILED;
        this.errorMessage = reason;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Repository     getRepository()                          { return repository; }
    public void           setRepository(Repository r)             { this.repository = r; }

    public ScanConfig     getConfig()                              { return config; }
    public void           setConfig(ScanConfig c)                 { this.config = c; }

    public List<Finding>  getAllFindings()                         { return allFindings; }
    public void           setAllFindings(List<Finding> f)         { this.allFindings = f; }

    public List<Finding>  getSample()                             { return sample; }
    public void           setSample(List<Finding> s)              { this.sample = s; }

    public EvaluationRun  getEvaluationRun()                      { return evaluationRun; }
    public void           setEvaluationRun(EvaluationRun r)       { this.evaluationRun = r; }

    public PipelineStatus getStatus()                             { return status; }
    public void           setStatus(PipelineStatus s)             { this.status = s; }

    public String         getErrorMessage()                       { return errorMessage; }
    public void           setErrorMessage(String msg)             { this.errorMessage = msg; }
}
