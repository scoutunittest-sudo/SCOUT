package zju.cst.aces.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.api.Logger;
import zju.cst.aces.api.Validator;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.dto.TestMessage;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractRunnerRuntimeFailureTest {

    @TempDir
    Path tempDir;

    @Test
    void runTestTreatsRuntimeFailuresAsFailedGeneration() throws Exception {
        Config config = new Config();
        config.setTestOutput(tempDir.resolve("tests"));
        config.setErrorOutput(tempDir.resolve("errors"));
        config.setPhaseType("SCOUT");
        config.setNoExecution(false);
        config.setLogger(new NoOpLogger());
        config.setValidator(new FailingRuntimeValidator());

        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setUnitTest("package example;\nclass BrokenTest {}\n");
        promptInfo.setMethodInfo(new MethodInfo(
                "Example",
                "target",
                "",
                "target()",
                "",
                Collections.emptyList(),
                Collections.emptyMap(),
                "",
                "",
                ""));

        boolean result = AbstractRunner.runTest(config, "example.BrokenTest", promptInfo, 0);

        assertFalse(result);
        assertFalse(Files.exists(tempDir.resolve("tests/example/BrokenTest.java")));
        Path errorPath = tempDir.resolve("errors/BrokenTest_ExecutionError_0.txt");
        assertTrue(Files.exists(errorPath));
        assertTrue(readString(errorPath).contains("1 test(s) failed"));
        assertEquals(TestMessage.ErrorType.RUNTIME_ERROR, promptInfo.getErrorMsg().getErrorType());
        assertTrue(promptInfo.getErrorMsg().getErrorMessage().get(0).contains("1 test(s) failed"));
    }

    @Test
    void runTestDefersScoutSuccessExportToCoverageGate() {
        Config config = new Config();
        config.setTestOutput(tempDir.resolve("tests"));
        config.setErrorOutput(tempDir.resolve("errors"));
        config.setPhaseType("SCOUT");
        config.setNoExecution(false);
        config.setLogger(new NoOpLogger());
        config.setValidator(new PassingRuntimeValidator());

        PromptInfo promptInfo = promptInfo("package example;\nclass PassingTest {}\n");

        boolean result = AbstractRunner.runTest(config, "example.PassingTest", promptInfo, 0);

        assertTrue(result);
        assertFalse(Files.exists(tempDir.resolve("tests/example/PassingTest.java")));
        assertEquals(0, config.getExportedTestCount().get());
    }

    @Test
    void runTestCountsExportedTestWhenValidationExportsFile() {
        Config config = new Config();
        config.setTestOutput(tempDir.resolve("tests"));
        config.setErrorOutput(tempDir.resolve("errors"));
        config.setPhaseType("TESTPILOT");
        config.setNoExecution(false);
        config.setStatusOnlyOutput(true);
        config.setLogger(new NoOpLogger());
        config.setValidator(new PassingRuntimeValidator());
        config.resetProgress(1);

        PromptInfo promptInfo = promptInfo("package example;\nclass PassingTest {}\n");

        boolean result = AbstractRunner.runTest(config, "example.PassingTest", promptInfo, 0);

        assertTrue(result);
        assertTrue(Files.exists(tempDir.resolve("tests/example/PassingTest.java")));
        assertEquals(1, config.getExportedTestCount().get());
        assertEquals(1, config.getValidUnitTestMethodCount().get());
    }

    @Test
    void runTestDoesNotCountScoutMethodValidUntilATestIsExported() {
        Config config = new Config();
        config.setTestOutput(tempDir.resolve("tests"));
        config.setErrorOutput(tempDir.resolve("errors"));
        config.setPhaseType("SCOUT");
        config.setNoExecution(false);
        config.setLogger(new NoOpLogger());
        config.setValidator(new PassingRuntimeValidator());
        config.resetProgress(1);

        PromptInfo promptInfo = promptInfo("package example;\nclass PassingTest {}\n");

        boolean result = AbstractRunner.runTest(config, "example.PassingTest", promptInfo, 0);

        assertTrue(result);
        assertEquals(0, config.getValidUnitTestMethodCount().get());
        assertTrue(config.getValidUnitTestMethodKeys().isEmpty());
    }

    @Test
    void runTestTreatsTimeoutAsFailedGeneration() {
        Config config = new Config();
        config.setTestOutput(tempDir.resolve("tests"));
        config.setErrorOutput(tempDir.resolve("errors"));
        config.setPhaseType("SCOUT");
        config.setNoExecution(false);
        config.setLogger(new NoOpLogger());
        config.setValidator(new TimeoutValidator());
        config.resetProgress(1);

        PromptInfo promptInfo = promptInfo("package example;\nclass TimeoutTest {}\n");

        boolean result = AbstractRunner.runTest(config, "example.TimeoutTest", promptInfo, 0);

        assertFalse(result);
        assertEquals(0, config.getValidUnitTestMethodCount().get());
        assertTrue(config.getValidUnitTestMethodKeys().isEmpty());
        assertEquals(1, config.getTimeoutCount().get());
    }

    private static class FailingRuntimeValidator implements Validator {
        @Override
        public boolean syntacticValidate(String code) {
            return true;
        }

        @Override
        public boolean semanticValidate(String code, String className, Path outputPath, PromptInfo promptInfo) {
            return true;
        }

        @Override
        public boolean runtimeValidate(String fullTestName) {
            return false;
        }

        @Override
        public boolean compile(String className, Path outputPath, PromptInfo promptInfo) {
            return true;
        }

        @Override
        public TestExecutionSummary execute(String fullTestName) {
            return new TestExecutionSummary() {
                @Override
                public long getTimeStarted() {
                    return 0;
                }

                @Override
                public long getTimeFinished() {
                    return 0;
                }

                @Override
                public long getTotalFailureCount() {
                    return 1;
                }

                @Override
                public long getContainersFoundCount() {
                    return 1;
                }

                @Override
                public long getContainersStartedCount() {
                    return 1;
                }

                @Override
                public long getContainersSkippedCount() {
                    return 0;
                }

                @Override
                public long getContainersAbortedCount() {
                    return 0;
                }

                @Override
                public long getContainersSucceededCount() {
                    return 1;
                }

                @Override
                public long getContainersFailedCount() {
                    return 0;
                }

                @Override
                public long getTestsFoundCount() {
                    return 1;
                }

                @Override
                public long getTestsStartedCount() {
                    return 1;
                }

                @Override
                public long getTestsSkippedCount() {
                    return 0;
                }

                @Override
                public long getTestsAbortedCount() {
                    return 0;
                }

                @Override
                public long getTestsSucceededCount() {
                    return 0;
                }

                @Override
                public long getTestsFailedCount() {
                    return 1;
                }

                @Override
                public void printTo(PrintWriter writer) {
                    writer.println("1 test failed");
                }

                @Override
                public void printFailuresTo(PrintWriter writer) {
                    writer.println("failure");
                }

                @Override
                public List<Failure> getFailures() {
                    return Collections.emptyList();
                }
            };
        }
    }

    private static class PassingRuntimeValidator extends FailingRuntimeValidator {
        @Override
        public TestExecutionSummary execute(String fullTestName) {
            return new TestExecutionSummary() {
                @Override
                public long getTimeStarted() {
                    return 0;
                }

                @Override
                public long getTimeFinished() {
                    return 0;
                }

                @Override
                public long getTotalFailureCount() {
                    return 0;
                }

                @Override
                public long getContainersFoundCount() {
                    return 1;
                }

                @Override
                public long getContainersStartedCount() {
                    return 1;
                }

                @Override
                public long getContainersSkippedCount() {
                    return 0;
                }

                @Override
                public long getContainersAbortedCount() {
                    return 0;
                }

                @Override
                public long getContainersSucceededCount() {
                    return 1;
                }

                @Override
                public long getContainersFailedCount() {
                    return 0;
                }

                @Override
                public long getTestsFoundCount() {
                    return 1;
                }

                @Override
                public long getTestsStartedCount() {
                    return 1;
                }

                @Override
                public long getTestsSkippedCount() {
                    return 0;
                }

                @Override
                public long getTestsAbortedCount() {
                    return 0;
                }

                @Override
                public long getTestsSucceededCount() {
                    return 1;
                }

                @Override
                public long getTestsFailedCount() {
                    return 0;
                }

                @Override
                public void printTo(PrintWriter writer) {
                    writer.println("1 test succeeded");
                }

                @Override
                public void printFailuresTo(PrintWriter writer) {
                }

                @Override
                public List<Failure> getFailures() {
                    return Collections.emptyList();
                }
            };
        }
    }

    private static class TimeoutValidator extends FailingRuntimeValidator {
        @Override
        public TestExecutionSummary execute(String fullTestName) {
            return null;
        }
    }

    private static PromptInfo promptInfo(String code) {
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setUnitTest(code);
        promptInfo.setMethodInfo(new MethodInfo(
                "Example",
                "target",
                "",
                "target()",
                "",
                Collections.emptyList(),
                Collections.emptyMap(),
                "",
                "",
                ""));
        return promptInfo;
    }

    private static String readString(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static class NoOpLogger implements Logger {
        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void error(String msg) {
        }

        @Override
        public void debug(String msg) {
        }
    }
}
