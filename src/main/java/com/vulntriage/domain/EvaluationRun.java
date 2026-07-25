package com.vulntriage.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Groups a set of LLM results into a named evaluation run.
 * Storing runs separately allows comparison between different models or
 * prompt versions over the same set of findings.
 */
public class EvaluationRun {

    private long              id;
    private String            name;
    private String            description;
    private LocalDateTime     createdAt;
    private List<Long>        sampleFindingIds = new ArrayList<>();

    public EvaluationRun() {}

    public EvaluationRun(String name, String description) {
        this.name        = name;
        this.description = description;
        this.createdAt   = LocalDateTime.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public long getId()                                    { return id; }
    public void setId(long id)                             { this.id = id; }

    public String getName()                                { return name; }
    public void setName(String name)                       { this.name = name; }

    public String getDescription()                         { return description; }
    public void setDescription(String description)         { this.description = description; }

    public LocalDateTime getCreatedAt()                    { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)      { this.createdAt = createdAt; }

    public List<Long> getSampleFindingIds()                { return sampleFindingIds; }
    public void setSampleFindingIds(List<Long> ids)        { this.sampleFindingIds = ids; }
}
