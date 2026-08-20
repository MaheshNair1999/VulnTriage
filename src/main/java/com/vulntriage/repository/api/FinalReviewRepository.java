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

    boolean isFlagged(long findingId);

    void setFlagged(long findingId, boolean flagged);

    /** All findings where manual verdict = TP. */
    List<Finding> findQualifyingFindings();
}
