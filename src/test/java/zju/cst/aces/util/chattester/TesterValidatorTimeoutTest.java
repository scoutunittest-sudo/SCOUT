package zju.cst.aces.util.chattester;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.dto.PromptInfo;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TesterValidatorTimeoutTest {
    @Test
    void semanticValidationReturnsFalseWhenCompilationTimesOut() {
        TesterValidator validator = new TesterValidator(new SlowCompiler(), 10, TimeUnit.MILLISECONDS);

        boolean result = validator.semanticValidate("class SlowTest {}", "SlowTest", Paths.get("/tmp/slow.txt"), new PromptInfo());

        assertFalse(result);
    }

    @Test
    void executeReturnsNullWhenTestRunTimesOut() {
        TesterValidator validator = new TesterValidator(new SlowCompiler(), 10, TimeUnit.MILLISECONDS);

        TestExecutionSummary summary = validator.execute("example.SlowTest");

        assertNull(summary);
    }

    @Test
    void executeTimeoutUsesDaemonWorkerSoTimedOutRunsDoNotBlockProgress() {
        ThreadRecordingCompiler compiler = new ThreadRecordingCompiler();
        TesterValidator validator = new TesterValidator(compiler, 10, TimeUnit.MILLISECONDS);

        TestExecutionSummary summary = validator.execute("example.SlowTest");

        assertNull(summary);
        assertTrue(compiler.wasRunOnDaemonThread());
    }

    @Test
    void runtimeValidationReturnsFalseWhenTestRunTimesOut() {
        TesterValidator validator = new TesterValidator(new SlowCompiler(), 10, TimeUnit.MILLISECONDS);

        boolean result = validator.runtimeValidate("example.SlowTest");

        assertFalse(result);
    }

    private static class SlowCompiler extends TesterCompiler {
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
}
