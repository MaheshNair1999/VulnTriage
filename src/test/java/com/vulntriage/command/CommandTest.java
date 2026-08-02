package com.vulntriage.command;

import com.vulntriage.domain.ManualReview;
import com.vulntriage.domain.enums.Verdict;
import com.vulntriage.repository.api.ManualReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Command pattern: {@link AssignVerdictCommand} and {@link CommandHistory}.
 *
 * Uses a simple in-memory review repository stub — no database.
 */
class CommandTest {

    private StubReviewRepo reviewRepo;

    @BeforeEach
    void setUp() {
        reviewRepo = new StubReviewRepo();
    }

    // ── AssignVerdictCommand.execute ──────────────────────────────────────

    @Test
    void execute_savesNewReview() {
        AssignVerdictCommand cmd = new AssignVerdictCommand(
            reviewRepo, 1L, Verdict.TP, "confirmed xss", null, null);
        cmd.execute();

        Optional<ManualReview> mr = reviewRepo.findByFindingId(1L);
        assertTrue(mr.isPresent());
        assertEquals(Verdict.TP, mr.get().getVerdict());
        assertEquals("confirmed xss", mr.get().getNotes());
    }

    // ── AssignVerdictCommand.undo — was unreviewed ─────────────────────────

    @Test
    void undo_whenNoPriorReview_deletesReview() {
        AssignVerdictCommand cmd = new AssignVerdictCommand(
            reviewRepo, 2L, Verdict.FP, "safe", null, null);
        cmd.execute();
        assertTrue(reviewRepo.findByFindingId(2L).isPresent());

        cmd.undo();
        assertTrue(reviewRepo.findByFindingId(2L).isEmpty(), "Review should be removed on undo");
    }

    // ── AssignVerdictCommand.undo — had prior review ───────────────────────

    @Test
    void undo_withPriorReview_restoresPreviousVerdict() {
        // Seed an existing review
        reviewRepo.saveOrUpdate(new ManualReview(3L, Verdict.REVIEW, "unsure"));

        AssignVerdictCommand cmd = new AssignVerdictCommand(
            reviewRepo, 3L, Verdict.TP, "confirmed", Verdict.REVIEW, "unsure");
        cmd.execute();
        assertEquals(Verdict.TP, reviewRepo.findByFindingId(3L).get().getVerdict());

        cmd.undo();
        ManualReview restored = reviewRepo.findByFindingId(3L).get();
        assertEquals(Verdict.REVIEW, restored.getVerdict());
        assertEquals("unsure", restored.getNotes());
    }

    // ── CommandHistory ─────────────────────────────────────────────────────

    @Test
    void history_executeAndUndo_revertsAction() {
        AssignVerdictCommand cmd = new AssignVerdictCommand(
            reviewRepo, 4L, Verdict.FP, "ok", null, null);

        CommandHistory history = new CommandHistory();
        history.executeAndPush(cmd);
        assertTrue(reviewRepo.findByFindingId(4L).isPresent());

        assertTrue(history.undo());
        assertTrue(reviewRepo.findByFindingId(4L).isEmpty());
    }

    @Test
    void history_undo_emptyHistory_returnsFalse() {
        CommandHistory history = new CommandHistory();
        assertFalse(history.undo(), "undo on empty history should return false");
    }

    @Test
    void history_size_tracksCommands() {
        CommandHistory history = new CommandHistory();
        assertEquals(0, history.size());
        history.executeAndPush(
            new AssignVerdictCommand(reviewRepo, 5L, Verdict.TP, "", null, null));
        assertEquals(1, history.size());
        history.undo();
        assertEquals(0, history.size());
    }

    @Test
    void history_boundedAt_maxSize_oldestDropped() {
        CommandHistory history = new CommandHistory(3);
        for (int i = 0; i < 5; i++) {
            history.executeAndPush(
                new AssignVerdictCommand(reviewRepo, (long) (100 + i), Verdict.FP, "", null, null));
        }
        assertEquals(3, history.size(), "History must not exceed maxSize");
    }

    @Test
    void history_canUndo_falseWhenEmpty() {
        CommandHistory history = new CommandHistory();
        assertFalse(history.canUndo());
        history.executeAndPush(
            new AssignVerdictCommand(reviewRepo, 6L, Verdict.TP, "", null, null));
        assertTrue(history.canUndo());
    }

    @Test
    void history_clear_emptiesStack() {
        CommandHistory history = new CommandHistory();
        history.executeAndPush(
            new AssignVerdictCommand(reviewRepo, 7L, Verdict.TP, "", null, null));
        history.clear();
        assertEquals(0, history.size());
    }

    // ── In-memory stub ─────────────────────────────────────────────────────

    static class StubReviewRepo implements ManualReviewRepository {

        private final List<ManualReview> store = new ArrayList<>();

        @Override
        public void saveOrUpdate(ManualReview review) {
            store.removeIf(r -> r.getFindingId() == review.getFindingId());
            store.add(review);
        }

        @Override
        public Optional<ManualReview> findByFindingId(long findingId) {
            return store.stream().filter(r -> r.getFindingId() == findingId).findFirst();
        }

        @Override
        public void deleteByFindingId(long findingId) {
            store.removeIf(r -> r.getFindingId() == findingId);
        }

        @Override public List<ManualReview> findAll()                           { return List.copyOf(store); }
        @Override public List<ManualReview> findByVerdict(Verdict v)            { return List.of(); }
        @Override public long countByVerdict(Verdict v)                         { return 0; }
        @Override public long countAll()                                         { return store.size(); }
        @Override public long countForFindingIds(List<Long> ids)                { return 0; }
    }
}
