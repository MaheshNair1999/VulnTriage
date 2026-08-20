package com.vulntriage.domain;

import com.vulntriage.domain.enums.FinalVerdict;
import java.time.LocalDateTime;

public class FinalReview {

    private long          id;
    private long          findingId;
    private FinalVerdict  verdict;
    private String        notes;
    private LocalDateTime reviewedAt;

    public FinalReview() {}

    public FinalReview(long findingId, FinalVerdict verdict, String notes) {
        this.findingId  = findingId;
        this.verdict    = verdict;
        this.notes      = notes;
        this.reviewedAt = LocalDateTime.now();
    }

    public long         getId()          { return id; }
    public void         setId(long id)   { this.id = id; }

    public long         getFindingId()              { return findingId; }
    public void         setFindingId(long findingId){ this.findingId = findingId; }

    public FinalVerdict getVerdict()                { return verdict; }
    public void         setVerdict(FinalVerdict v)  { this.verdict = v; }

    public String       getNotes()                  { return notes; }
    public void         setNotes(String notes)      { this.notes = notes; }

    public LocalDateTime getReviewedAt()                     { return reviewedAt; }
    public void          setReviewedAt(LocalDateTime t)      { this.reviewedAt = t; }
}
