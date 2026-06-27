package zju.cst.aces.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MethodWorkSchedulerTest {

    @Test
    void continuesRunningRemainingTasksWhenOneTaskFails() {
        AtomicInteger processed = new AtomicInteger(0);

        MethodWorkScheduler.run(Arrays.asList("first", "broken", "last"), 1,
                new MethodWorkScheduler.Worker<String>() {
                    @Override
                    public void run(String task) {
                        if ("broken".equals(task)) {
                            throw new RuntimeException("boom");
                        }
                        processed.incrementAndGet();
                    }
                });

        assertEquals(2, processed.get());
    }
}
