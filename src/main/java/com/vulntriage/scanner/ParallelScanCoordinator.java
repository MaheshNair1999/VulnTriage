package com.vulntriage.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

/**
 * Coordinates concurrent execution of multiple scan tasks.
 *
 * <p>Each repository scan is submitted as a {@link Callable}{@code <String>}, where
 * the returned string is a human-readable status/result message (e.g. "Repo X: 12 findings
 * saved" or an error description). A per-task failure does <em>not</em> cancel other tasks.
 *
 * <p>Pool size is {@code min(4, tasks.size())} so a single-repo scan uses one thread
 * and large batches are capped at four concurrent scanners to avoid overwhelming the host.
 *
 * <p>This class is extracted from the UI layer so that coordination logic can be tested
 * headless without JavaFX. The {@code onResult} callback is invoked on the calling
 * (executor) thread for each task as it completes; callers that need to update JavaFX
 * nodes must wrap the callback body in {@code Platform.runLater}.
 */
public class ParallelScanCoordinator {

    /** Maximum concurrent scanner threads. */
    static final int MAX_POOL_SIZE = 4;

    /**
     * Run all {@code tasks} concurrently, invoking {@code onResult} for each one.
     *
     * @param tasks    scan callables; each returns a status string
     * @param onResult called after each task with {@code (index, result)} where
     *                 {@code result} is the returned string on success or an error
     *                 message on failure; index is 0-based
     */
    public void run(List<Callable<String>> tasks,
                    BiConsumer<Integer, String> onResult) {
        if (tasks.isEmpty()) return;

        int poolSize = Math.min(MAX_POOL_SIZE, tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        List<Future<String>> futures = new ArrayList<>(tasks.size());
        for (Callable<String> task : tasks) {
            futures.add(executor.submit(task));
        }
        executor.shutdown(); // no new tasks; existing ones keep running

        for (int i = 0; i < futures.size(); i++) {
            String result;
            try {
                result = futures.get(i).get();
            } catch (Exception e) {
                // Per-repo failure — report it but do not cancel sibling tasks
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                result = "ERROR: " + cause.getMessage();
            }
            onResult.accept(i, result);
        }
    }
}
