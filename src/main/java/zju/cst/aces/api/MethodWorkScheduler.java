package zju.cst.aces.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class MethodWorkScheduler {

    private MethodWorkScheduler() {
    }

    interface Worker<T> {
        void run(T task) throws Exception;
    }

    interface FailureHandler<T> {
        void onFailure(T task, Exception failure);
    }

    static <T> void run(List<T> tasks, int maxWorkers, Worker<T> worker) {
        run(tasks, maxWorkers, worker, null);
    }

    static <T> void run(List<T> tasks, int maxWorkers, Worker<T> worker, FailureHandler<T> failureHandler) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        int workerCount = workerCount(maxWorkers, tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<ScheduledFuture<T>> futures = new ArrayList<ScheduledFuture<T>>();
        try {
            for (final T task : tasks) {
                Future<?> future = executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            worker.run(task);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                futures.add(new ScheduledFuture<T>(task, future));
            }
            for (ScheduledFuture<T> scheduled : futures) {
                try {
                    scheduled.future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    notifyFailure(failureHandler, scheduled.task, e);
                    return;
                } catch (Exception e) {
                    notifyFailure(failureHandler, scheduled.task, e);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> void notifyFailure(FailureHandler<T> failureHandler, T task, Exception failure) {
        if (failureHandler != null) {
            failureHandler.onFailure(task, failure);
        }
    }

    static int workerCount(int maxWorkers, int taskCount) {
        int safeTasks = Math.max(1, taskCount);
        int safeWorkers = Math.max(1, maxWorkers);
        return Math.min(safeWorkers, safeTasks);
    }

    static <T extends WeightedWork> List<T> longestFirst(List<T> tasks) {
        List<T> sorted = new ArrayList<T>(tasks);
        Collections.sort(sorted, new Comparator<T>() {
            @Override
            public int compare(T left, T right) {
                long diff = right.estimatedNanos() - left.estimatedNanos();
                if (diff > 0L) {
                    return 1;
                }
                if (diff < 0L) {
                    return -1;
                }
                return 0;
            }
        });
        return sorted;
    }

    interface WeightedWork {
        long estimatedNanos();
    }

    private static class ScheduledFuture<T> {
        private final T task;
        private final Future<?> future;

        private ScheduledFuture(T task, Future<?> future) {
            this.task = task;
            this.future = future;
        }
    }
}
