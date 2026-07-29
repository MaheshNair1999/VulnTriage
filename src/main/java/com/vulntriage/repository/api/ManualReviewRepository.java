package com.vulntriage.repository.api;

import com.vulntriage.domain.ManualReview;
import com.vulntriage.domain.enums.Verdict;
import java.util.List;
import java.util.Optional;

public interface ManualReviewRepository {

    /** Save a new review, or update it if one already exists for this finding. */
    void saveOrUpdate(ManualReview review);

    Optional<ManualReview> findByFindingId(long findingId);

    List<ManualReview> findAll();

    List<ManualReview> findByVerdict(Verdict verdict);

    long countByVerdict(Verdict verdict);

    long countAll();

    /** Count reviews only for the given finding IDs — scoped to the current view. */
    long countForFindingIds(List<Long> findingIds);

    /** Remove the review for a finding (used by undo when the finding had no prior review). */
    void deleteByFindingId(long findingId);
}
