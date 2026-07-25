package com.vulntriage.domain;

import com.vulntriage.domain.enums.Verdict;
import java.time.LocalDateTime;

/**
 * Records a human analyst's verdict on a specific finding.
 * One finding has at most one ManualReview (enforced by UNIQUE constraint in DB).
 */
public class ManualReview {

    private long          id;
    private long          findingId;
    private Verdict       verdict;
    private String        notes;
    private LocalDateTime reviewedAt;

    public ManualReview() {}

    public ManualReview(long findingId, Verdict verdict, String notes) {
        this.findingId  = findingId;
        this.verdict    = verdict;
        this.notes      = notes;
        this.reviewedAt = LocalDateTime.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public long getId()                           { return id; }
    public void setId(long id)                    { this.id = id; }

    public long getFindingId()                    { return findingId; }
    public void setFindingId(long findingId)      { this.findingId = findingId; }

    public Verdict getVerdict()                   { return verdict; }
    public void setVerdict(Verdict verdict)       { this.verdict = verdict; }

    public String getNotes()                      { return notes; }
    public void setNotes(String notes)            { this.notes = notes; }

    public LocalDateTime getReviewedAt()                   { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt)    { this.reviewedAt = reviewedAt; }
}
