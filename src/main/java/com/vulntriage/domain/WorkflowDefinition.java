package com.vulntriage.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A complete user-defined security workflow.
 *
 * Stored as JSON in the database and loaded at runtime by WorkflowParser.
 * The engine instantiates PipelineStage objects from the steps list
 * and chains them for execution.
 */
public class WorkflowDefinition {

    private long              id;
    private String            name;
    private String            description;
    private List<WorkflowStep> steps     = new ArrayList<>();
    private LocalDateTime     createdAt;
    private LocalDateTime     updatedAt;

    public WorkflowDefinition() {}

    public WorkflowDefinition(String name, String description) {
        this.name        = name;
        this.description = description;
        this.createdAt   = LocalDateTime.now();
        this.updatedAt   = LocalDateTime.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public long   getId()                              { return id; }
    public void   setId(long id)                       { this.id = id; }

    public String getName()                            { return name; }
    public void   setName(String name)                 { this.name = name; }

    public String getDescription()                     { return description; }
    public void   setDescription(String description)   { this.description = description; }

    public List<WorkflowStep> getSteps()               { return steps; }
    public void setSteps(List<WorkflowStep> steps)     { this.steps = steps; }

    public LocalDateTime getCreatedAt()                { return createdAt; }
    public void setCreatedAt(LocalDateTime t)          { this.createdAt = t; }

    public LocalDateTime getUpdatedAt()                { return updatedAt; }
    public void setUpdatedAt(LocalDateTime t)          { this.updatedAt = t; }

    /** Convenience — add a step to the end of the workflow. */
    public void addStep(WorkflowStep step) {
        steps.add(step);
    }

    @Override
    public String toString() {
        return "WorkflowDefinition{name='" + name + "', steps=" + steps.size() + "}";
    }
}
