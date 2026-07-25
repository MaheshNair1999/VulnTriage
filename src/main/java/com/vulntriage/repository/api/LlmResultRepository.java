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

    /** Delete all LLM results for a finding (allows re-triage). */
    void deleteByFindingId(long findingId);
}
