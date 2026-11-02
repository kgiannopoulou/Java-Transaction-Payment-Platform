package com.kgiannopoulou.ledgerforge.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Fires {@code n} tasks at the same instant: every task runs on its own virtual thread and waits
 * on a start gate, so they hit the API together rather than trickling in as threads are created.
 */
public final class Concurrently {

    private Concurrently() {
    }

    public static <T> List<T> run(int n, IntFunction<T> task) {
        return run(n, n, task);
    }

    /**
     * Like {@link #run(int, IntFunction)} but with at most {@code maxInFlight} tasks executing at
     * once. Used for large bursts: opening hundreds of TCP connections in the same millisecond
     * overflows the OS accept backlog (a client-side artefact, not what the test is about).
     */
    public static <T> List<T> run(int n, int maxInFlight, IntFunction<T> task) {
        Semaphore inFlight = new Semaphore(maxInFlight);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(n);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    inFlight.acquire();
                    try {
                        return task.apply(index);
                    } finally {
                        inFlight.release();
                    }
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Tasks did not start in time");
            }
            go.countDown();

            List<T> results = new ArrayList<>(n);
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Concurrent run failed", e);
        }
    }
}
