package zju.cst.aces.api.impl;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.util.TestCompiler;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorImplTimeoutTest {
    @Test
    void semanticValidationReturnsFalseWhenCompilationTimesOut() {
        ValidatorImpl validator = new ValidatorImpl(new SlowCompiler(), 10, TimeUnit.MILLISECONDS);

        boolean result = validator.semanticValidate("class SlowTest {}", "SlowTest", Paths.get("/tmp/slow.txt"), new PromptInfo());

        assertFalse(result);
    }

    @Test
    void executeReturnsNullWhenTestRunTimesOut() {
        ValidatorImpl validator = new ValidatorImpl(new SlowCompiler(), 10, TimeUnit.MILLISECONDS);

        TestExecutionSummary summary = validator.execute("example.SlowTest");

        assertNull(summary);
    }

    @Test
    void executeTimeoutUsesDaemonWorkerSoTimedOutRunsDoNotBlockProgress() {
        ThreadRecordingCompiler compiler = new ThreadRecordingCompiler();
        ValidatorImpl validator = new ValidatorImpl(compiler, 10, TimeUnit.MILLISECONDS);

        TestExecutionSummary summary = validator.execute("example.SlowTest");

        assertNull(summary);
        assertTrue(compiler.wasRunOnDaemonThread());
    }

    @Test
    void runtimeValidationReturnsFalseWhenTestRunTimesOut() {
        ValidatorImpl validator = new ValidatorImpl(new SlowCompiler(), 10, TimeUnit.MILLISECONDS);

        boolean result = validator.runtimeValidate("example.SlowTest");

        assertFalse(result);
    }

    @Test
    void semanticValidationKeepsConcurrentSourceCodeIsolated() throws Exception {
        InterleavingCompiler compiler = new InterleavingCompiler();
        ValidatorImpl validator = new ValidatorImpl(compiler, 1, TimeUnit.SECONDS);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> validator.semanticValidate(
                    "class FirstTest {}", "FirstTest", Paths.get("/tmp/first.txt"), new PromptInfo()));
            assertTrue(compiler.awaitFirstCompile());

            Future<Boolean> second = executor.submit(() -> validator.semanticValidate(
                    "class SecondTest {}", "SecondTest", Paths.get("/tmp/second.txt"), new PromptInfo()));

            assertTrue(first.get());
            assertTrue(second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static class SlowCompiler extends TestCompiler {
        private SlowCompiler() {
            super(Paths.get("/tmp/tests"), Paths.get("/tmp/build"), Paths.get("/tmp/target"), Collections.emptyList());
        }

        @Override
        public boolean compileTest(String className, Path outputPath, PromptInfo promptInfo) {
            sleep();
            return true;
        }

        @Override
        public TestExecutionSummary executeTest(String fullTestName) {
            sleep();
            return null;
        }

        private void sleep() {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class ThreadRecordingCompiler extends SlowCompiler {
        private volatile boolean daemonThread;

        @Override
        public TestExecutionSummary executeTest(String fullTestName) {
            daemonThread = Thread.currentThread().isDaemon();
            return super.executeTest(fullTestName);
        }

        private boolean wasRunOnDaemonThread() {
            return daemonThread;
        }
    }

    private static class InterleavingCompiler extends TestCompiler {
        private final CountDownLatch firstCompileEntered = new CountDownLatch(1);
        private final CountDownLatch secondCompileEntered = new CountDownLatch(1);

        private InterleavingCompiler() {
            super(Paths.get("/tmp/tests"), Paths.get("/tmp/build"), Paths.get("/tmp/target"), Collections.emptyList());
        }

        public boolean compileTest(String code, String className, Path outputPath, PromptInfo promptInfo) {
            if ("FirstTest".equals(className)) {
                firstCompileEntered.countDown();
                awaitSecondCompile();
                return code.contains("FirstTest");
            }
            if ("SecondTest".equals(className)) {
                secondCompileEntered.countDown();
                return code.contains("SecondTest");
            }
            return false;
        }

        @Override
        public boolean compileTest(String className, Path outputPath, PromptInfo promptInfo) {
            if ("FirstTest".equals(className)) {
                firstCompileEntered.countDown();
                awaitSecondCompile();
                return getCode().contains("FirstTest");
            }
            if ("SecondTest".equals(className)) {
                secondCompileEntered.countDown();
                return getCode().contains("SecondTest");
            }
            return false;
        }

        private boolean awaitFirstCompile() throws InterruptedException {
            return firstCompileEntered.await(1, TimeUnit.SECONDS);
        }

        private void awaitSecondCompile() {
            try {
                assertTrue(secondCompileEntered.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
