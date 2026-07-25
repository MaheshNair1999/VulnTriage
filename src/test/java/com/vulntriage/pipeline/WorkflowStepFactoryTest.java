package com.vulntriage.pipeline;

import com.vulntriage.domain.WorkflowStep;
import com.vulntriage.domain.WorkflowStep.StepType;
import com.vulntriage.event.ProgressLogger;
import com.vulntriage.pipeline.stages.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowStepFactoryTest {

    static {
        System.setProperty("vulntriage.db.url", "jdbc:sqlite::memory:");
    }

    private WorkflowStepFactory factory;

    @BeforeEach
    void setup() {
        factory = new WorkflowStepFactory(List.of(new ProgressLogger()));
    }

    @Test
    void create_scanStep_returnsScanStage() {
        WorkflowStep step = new WorkflowStep(StepType.SCAN,
            Map.of("scanner", "semgrep", "ruleset", "p/security-audit"));
        var stage = factory.create(step);
        assertInstanceOf(ScanStage.class, stage);
    }

    @Test
    void create_filterStep_returnsFilterStage() {
        WorkflowStep step = new WorkflowStep(StepType.FILTER,
            Map.of("condition", "severity >= WARNING"));
        var stage = factory.create(step);
        assertInstanceOf(FilterStage.class, stage);
    }

    @Test
    void create_sampleStep_returnsSampleStage() {
        WorkflowStep step = new WorkflowStep(StepType.SAMPLE,
            Map.of("size", "100"));
        var stage = factory.create(step);
        assertInstanceOf(SampleStage.class, stage);
    }

    @Test
    void create_triageStep_returnsTriageStage() {
        WorkflowStep step = new WorkflowStep(StepType.TRIAGE,
            Map.of("model", "qwen3:8b"));
        var stage = factory.create(step);
        assertInstanceOf(TriageStage.class, stage);
    }

    @Test
    void create_scoreStep_returnsScoreStage() {
        WorkflowStep step = new WorkflowStep(StepType.SCORE, Map.of());
        var stage = factory.create(step);
        assertInstanceOf(ScoreStage.class, stage);
    }

    @Test
    void create_reportStep_returnsEvaluateStage() {
        WorkflowStep step = new WorkflowStep(StepType.REPORT,
            Map.of("formats", "json,csv"));
        var stage = factory.create(step);
        assertInstanceOf(EvaluateStage.class, stage);
    }

    @Test
    void create_filterStep_withInvalidCondition_throwsException() {
        WorkflowStep step = new WorkflowStep(StepType.FILTER,
            Map.of("condition", "severity BADOP WARNING"));
        assertThrows(Exception.class, () -> factory.create(step));
    }
}
