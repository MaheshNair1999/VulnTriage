package com.vulntriage.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Coordinates concurrent execution of multiple scan tasks.
 *
 * <p>Each repository scan is submitted as a {@link Callable}{@code <String>}, where
 * the returned string is a human-readable status/result message (e.g. "Repo X: 12 findings
 * saved" or an error description). A per-task failure does <em>not</em> cancel other tasks.
 *
 * <p>Pool size is {@code min(availableProcessors, 8, tasks.size())} — scales to the
 * host's CPU core count automatically, capped at 8 to avoid spawning excessive scanner
 * processes on high-core-count machines.
 *
 * <p>This class is extracted from the UI layer so that coordination logic can be tested
 * headless without JavaFX. The {@code onResult} callback is invoked on the calling
 * (executor) thread for each task as it completes; callers that need to update JavaFX
 * nodes must wrap the callback body in {@code Platform.runLater}.
 */
public class ParallelScanCoordinator {

    /** Hard ceiling on concurrent scanner processes regardless of core count. */
    static final int MAX_POOL_SIZE = 8;

    /** Per-task timeout in minutes — prevents a hung scanner from blocking forever. */
    static final long TASK_TIMEOUT_MINUTES = 30;

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

        int cores    = Runtime.getRuntime().availableProcessors();
        int poolSize = Math.min(Math.min(cores, MAX_POOL_SIZE), tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        List<Future<String>> futures = new ArrayList<>(tasks.size());
        for (Callable<String> task : tasks) {
            futures.add(executor.submit(task));
        }
        executor.shutdown(); // no new tasks; existing ones keep running

        for (int i = 0; i < futures.size(); i++) {
            String result;
            try {
                result = futures.get(i).get(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                result = "ERROR: Scan timed out after " + TASK_TIMEOUT_MINUTES + " minutes";
            } catch (Exception e) {
                // Per-repo failure — report it but do not cancel sibling tasks
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                result = "ERROR: " + cause.getMessage();
            }
            onResult.accept(i, result);
        }
    }
}
