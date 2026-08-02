package com.vulntriage.command;

import com.vulntriage.domain.ManualReview;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.ManualReviewRepository;

/**
 * Command pattern — encapsulates a single "assign verdict" action in the Review screen.
 *
 * <p>Stores both the new verdict/notes and the previous state so that {@link #undo()} can
 * restore the review to exactly where it was before the analyst pressed T/F/R:
 * <ul>
 *   <li>If the finding had no prior review, undo deletes the newly created review row.</li>
 *   <li>If the finding had an existing review, undo restores the previous verdict and notes.</li>
 * </ul>
 */
public class AssignVerdictCommand implements Command {

    private final ManualReviewRepository reviewRepo;
    private final long   findingId;
    private final Verdict newVerdict;
    private final String  newNotes;
    private final Verdict previousVerdict; // null when the finding was previously unreviewed
    private final String  previousNotes;

    /**
     * @param reviewRepo      repository used to persist the review
     * @param findingId       primary key of the finding being reviewed
     * @param newVerdict      the verdict assigned by this command
     * @param newNotes        the notes entered by the analyst
     * @param previousVerdict the verdict that existed before this command (null if none)
     * @param previousNotes   the notes that existed before this command (null if none)
     */
    public AssignVerdictCommand(ManualReviewRepository reviewRepo,
                                long findingId,
                                Verdict newVerdict,
                                String newNotes,
                                Verdict previousVerdict,
                                String previousNotes) {
        this.reviewRepo      = reviewRepo;
        this.findingId       = findingId;
        this.newVerdict      = newVerdict;
        this.newNotes        = newNotes;
        this.previousVerdict = previousVerdict;
        this.previousNotes   = previousNotes;
    }

    @Override
    public void execute() {
        reviewRepo.saveOrUpdate(new ManualReview(findingId, newVerdict, newNotes));
    }

    @Override
    public void undo() {
        if (previousVerdict != null) {
            reviewRepo.saveOrUpdate(new ManualReview(findingId, previousVerdict, previousNotes));
        } else {
            reviewRepo.deleteByFindingId(findingId);
        }
    }

    // Accessors used by ReviewView to refresh the UI after undo
    public long    getFindingId()       { return findingId; }
    public Verdict getPreviousVerdict() { return previousVerdict; }
    public String  getPreviousNotes()   { return previousNotes; }
}
