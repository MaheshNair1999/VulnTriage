package com.vulntriage.repository.api;

import com.vulntriage.domain.LlmResult;
import java.util.List;
import java.util.Optional;

public interface LlmResultRepository {

    void save(LlmResult result);

    Optional<LlmResult> findByFindingIdAndRunId(long findingId, long evaluationRunId);

    List<LlmResult> findByEvaluationRunId(long evaluationRunId);

    long countByEvaluationRunId(long evaluationRunId);

    /** Returns true if this finding already has any LLM result saved (any run). */
    boolean existsByFindingId(long findingId);

    /** Returns the most recent LLM result for a finding across all runs. */
    Optional<LlmResult> findByFindingId(long findingId);

    /** Returns the most recent result for a finding with a specific prompt version. */
    default Optional<LlmResult> findByFindingIdAndPromptVersion(long findingId, String promptVersion) {
        return findAll().stream()
            .filter(r -> r.getFindingId() == findingId && promptVersion.equals(r.getPromptVersion()))
            .reduce((a, b) -> b);
    }

    /** Delete all LLM results for a finding across all runs (used by regular triage). */
    void deleteByFindingId(long findingId);

    /** Delete the LLM result for a finding within one specific run (used by targeted triage). */
    void deleteByFindingIdAndRunId(long findingId, long evaluationRunId);

    /** Delete all results for a finding with a specific prompt version (targeted force re-triage). */
    default void deleteByFindingIdAndPromptVersion(long findingId, String promptVersion) {};

    /** Returns all LLM results with the given prompt version (e.g. "v1.0", "v2.0"). */
    List<LlmResult> findAllByPromptVersion(String promptVersion);

    /** Returns every LLM result across all runs and all prompt versions. */
    List<LlmResult> findAll();
}
