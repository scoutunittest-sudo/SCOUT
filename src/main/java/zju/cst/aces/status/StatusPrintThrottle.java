package zju.cst.aces.status;

import java.util.function.LongSupplier;

final class StatusPrintThrottle {
    static final long DEFAULT_INTERVAL_MILLIS = 200L;

    private final long intervalMillis;
    private final LongSupplier clock;
    private long lastPrintMillis = Long.MIN_VALUE;

    StatusPrintThrottle() {
        this(DEFAULT_INTERVAL_MILLIS, System::currentTimeMillis);
    }

    StatusPrintThrottle(long intervalMillis, LongSupplier clock) {
        this.intervalMillis = Math.max(0L, intervalMillis);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    boolean shouldPrint(boolean force) {
        long now = clock.getAsLong();
        if (force || intervalMillis == 0L || lastPrintMillis == Long.MIN_VALUE
                || now - lastPrintMillis >= intervalMillis) {
            lastPrintMillis = now;
            return true;
        }
        return false;
    }
}
