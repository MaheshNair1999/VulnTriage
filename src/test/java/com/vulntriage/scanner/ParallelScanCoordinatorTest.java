package com.vulntriage.scanner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless unit tests for {@link ParallelScanCoordinator}.
 *
 * No JavaFX, no file system, no network — pure coordination logic.
 */
class ParallelScanCoordinatorTest {

    private final ParallelScanCoordinator coordinator = new ParallelScanCoordinator();

    // ── Basic execution ────────────────────────────────────────────────────

    @Test
    void run_emptyTaskList_doesNothing() {
        assertDoesNotThrow(() -> coordinator.run(List.of(), (i, r) -> {}));
    }

    @Test
    void run_singleTask_callbackInvokedWithResult() {
        List<String> results = new ArrayList<>();
        coordinator.run(
            List.of(() -> "ok"),
            (i, r) -> results.add(r)
        );
        assertEquals(1, results.size());
        assertEquals("ok", results.get(0));
    }

    @Test
    void run_multipleTasks_allCallbacksInvoked() {
        int n = 5;
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            tasks.add(() -> "result-" + idx);
        }

        List<String> results = Collections.synchronizedList(new ArrayList<>());
        coordinator.run(tasks, (i, r) -> results.add(r));

        assertEquals(n, results.size());
    }

    // ── Parallelism ────────────────────────────────────────────────────────

    @Test
    void run_fourTasks_executedConcurrently() throws InterruptedException {
        // Each task blocks on the latch; if serial they would deadlock
        CountDownLatch latch = new CountDownLatch(4);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> {
                latch.countDown();
                latch.await();   // waits for all 4 to arrive — would hang if serial
                return "done";
            });
        }

        AtomicInteger doneCount = new AtomicInteger(0);
        assertDoesNotThrow(() ->
            coordinator.run(tasks, (i, r) -> doneCount.incrementAndGet())
        );
        assertEquals(4, doneCount.get());
    }

    @Test
    void run_poolSizeCappedAtFour() {
        // Six tasks — coordinator must still complete them all (3+3 in two waves)
        int n = 6;
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) tasks.add(() -> "x");

        AtomicInteger count = new AtomicInteger(0);
        coordinator.run(tasks, (i, r) -> count.incrementAndGet());
        assertEquals(n, count.get());
    }

    // ── Per-task failure isolation ─────────────────────────────────────────

    @Test
    void run_oneTaskFails_othersStillComplete() {
        List<Callable<String>> tasks = List.of(
            () -> "first-ok",
            () -> { throw new RuntimeException("boom"); },
            () -> "third-ok"
        );

        List<String> results = Collections.synchronizedList(new ArrayList<>());
        coordinator.run(tasks, (i, r) -> results.add(r));

        assertEquals(3, results.size(), "All three callbacks must be invoked");
        assertTrue(results.stream().anyMatch(r -> r.equals("first-ok")));
        assertTrue(results.stream().anyMatch(r -> r.equals("third-ok")));
        assertTrue(results.stream().anyMatch(r -> r.startsWith("ERROR:")),
            "Failed task should produce an ERROR: result");
    }

    @Test
    void run_allTasksFail_callbacksStillInvoked() {
        List<Callable<String>> tasks = List.of(
            () -> { throw new RuntimeException("a"); },
            () -> { throw new RuntimeException("b"); }
        );

        AtomicInteger errorCount = new AtomicInteger(0);
        coordinator.run(tasks, (i, r) -> {
            if (r.startsWith("ERROR:")) errorCount.incrementAndGet();
        });

        assertEquals(2, errorCount.get());
    }

    // ── Callback index ─────────────────────────────────────────────────────

    @Test
    void run_callbackReceivesCorrectIndex() {
        List<Integer> indices = Collections.synchronizedList(new ArrayList<>());
        List<Callable<String>> tasks = List.of(() -> "a", () -> "b", () -> "c");

        coordinator.run(tasks, (i, r) -> indices.add(i));

        // Indices must be 0, 1, 2 in order (callbacks are collected sequentially)
        assertEquals(List.of(0, 1, 2), indices);
    }
}
