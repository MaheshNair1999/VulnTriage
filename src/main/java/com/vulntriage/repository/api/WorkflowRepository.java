package com.vulntriage.repository.api;

import com.vulntriage.domain.WorkflowDefinition;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for WorkflowDefinition persistence.
 *
 * Workflows are stored as JSON blobs in the database so the step
 * structure is fully flexible without schema migrations.
 */
public interface WorkflowRepository {

    /** Persist a new workflow. Sets the generated ID on the object. */
    void save(WorkflowDefinition workflow);

    Optional<WorkflowDefinition> findById(long id);

    List<WorkflowDefinition> findAll();

    /** Update an existing workflow (replaces its JSON). */
    void update(WorkflowDefinition workflow);

    void delete(long id);
}
