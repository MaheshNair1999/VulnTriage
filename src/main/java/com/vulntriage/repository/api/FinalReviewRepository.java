package com.vulntriage.repository.api;

import com.vulntriage.domain.Finding;
import com.vulntriage.domain.FinalReview;

import java.util.List;
import java.util.Optional;

public interface FinalReviewRepository {

    void save(FinalReview review);

    void update(FinalReview review);

    Optional<FinalReview> findByFindingId(long findingId);

    List<FinalReview> findAll();

    void deleteByFindingId(long findingId);

    /** All findings where manual verdict = TP and >= 2 distinct LLM prompt versions also said TP. */
    List<Finding> findQualifyingFindings();
}
