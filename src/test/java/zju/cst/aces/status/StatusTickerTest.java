package zju.cst.aces.status;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusTickerTest {

    @Test
    void tickerRepaintsImmediatelyAndThenAtFixedIntervalAndOnClose() throws Exception {
        AtomicInteger repaints = new AtomicInteger(0);

        StatusTicker ticker = new StatusTicker(repaints::incrementAndGet, 20L);
        ticker.start();
        Thread.sleep(75L);
        ticker.close();

        assertTrue(repaints.get() >= 4);
    }
}
