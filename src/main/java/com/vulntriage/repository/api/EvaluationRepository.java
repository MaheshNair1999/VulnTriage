package com.vulntriage.repository.api;

import com.vulntriage.domain.EvaluationRun;
import java.util.List;
import java.util.Optional;

public interface EvaluationRepository {

    void save(EvaluationRun run);

    Optional<EvaluationRun> findById(long id);

    List<EvaluationRun> findAll();

    void saveSampleFinding(long evaluationRunId, long findingId);

    List<Long> findSampleFindingIds(long evaluationRunId);

    void deleteById(long id);
}
