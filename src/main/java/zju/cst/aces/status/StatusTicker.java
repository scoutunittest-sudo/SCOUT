package zju.cst.aces.status;

import zju.cst.aces.api.config.Config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StatusTicker implements AutoCloseable {
    public static final long DEFAULT_INTERVAL_MILLIS = 1000L;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

    private final Runnable repaint;
    private final long intervalMillis;
    private final ScheduledExecutorService executor;
    private final boolean enabled;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ScheduledFuture<?> future;

    StatusTicker(Runnable repaint, long intervalMillis) {
        this(repaint, intervalMillis, true);
    }

    private StatusTicker(Runnable repaint, long intervalMillis, boolean enabled) {
        this.repaint = repaint == null ? new Runnable() {
            @Override
            public void run() {
            }
        } : repaint;
        this.intervalMillis = Math.max(1L, intervalMillis);
        this.enabled = enabled;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chatunitest-status-ticker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static StatusTicker generation(final Config config) {
        final StatusWindow statusWindow = new StatusWindow();
        if (!shouldStart(config)) {
            return noop();
        }
        return new StatusTicker(new Runnable() {
            @Override
            public void run() {
                statusWindow.printNow(config);
            }
        }, DEFAULT_INTERVAL_MILLIS);
    }

    public static StatusTicker coverage(final Config config) {
        if (!shouldStart(config)) {
            return noop();
        }
        if (config != null && config.isCoverageOnlyMode()) {
            final CoverageStatusWindow coverageStatusWindow = new CoverageStatusWindow();
            return new StatusTicker(new Runnable() {
                @Override
                public void run() {
                    coverageStatusWindow.printNow(config);
                }
            }, DEFAULT_INTERVAL_MILLIS);
        }
        return generation(config);
    }

    private static boolean shouldStart(Config config) {
        return config != null && config.isStatusOnlyOutput();
    }

    private static StatusTicker noop() {
        return new StatusTicker(new Runnable() {
            @Override
            public void run() {
            }
        }, DEFAULT_INTERVAL_MILLIS, false);
    }

    public StatusTicker start() {
        if (!enabled) {
            return this;
        }
        if (started.get()) {
            return this;
        }
        if (!ACTIVE.compareAndSet(false, true)) {
            executor.shutdownNow();
            return noop();
        }
        if (started.compareAndSet(false, true)) {
            repaintSafely();
            future = executor.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    repaintSafely();
                }
            }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
        return this;
    }

    private synchronized void repaintSafely() {
        if (closed.get()) {
            return;
        }
        try {
            repaint.run();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        if (!enabled) {
            executor.shutdownNow();
            return;
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (future != null) {
            future.cancel(true);
        }
        executor.shutdownNow();
        try {
            repaint.run();
        } catch (RuntimeException ignored) {
        } finally {
            ACTIVE.compareAndSet(true, false);
        }
    }
}
