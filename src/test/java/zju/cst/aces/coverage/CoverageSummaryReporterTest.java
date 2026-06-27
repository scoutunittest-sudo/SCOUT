package zju.cst.aces.coverage;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zju.cst.aces.api.Project;
import zju.cst.aces.api.config.Config;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageSummaryReporterTest {
    @TempDir
    Path tempDir;

    @Test
    void reporterRunsGeneratedTestsAndWritesCoverageSummary() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-tests");
        Path tmpOutput = tempDir.resolve("tmp");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput);

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        if (input > 0) {",
                "            return 1;",
                "        }",
                "        return -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .cname("example.CoverageTarget")
                .build();
        config.markLlmCallAttempt();
        config.markLlmCallAttempt();
        config.markLlmCallSuccess();
        config.recordTiming("LLM", 2_000_000L);
        config.recordTiming("coverage", 3_000_000L);
        Path generatedTestClasses = config.getCompileOutputPath();
        Files.createDirectories(generatedTestClasses);

        Path testSource = testOutput.resolve("example/CoverageTarget_0_Test.java");
        Files.createDirectories(testSource.getParent());
        Files.write(testSource, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTarget_0_Test {",
                "    @Test",
                "    void coversPositiveBranch() {",
                "        assertEquals(1, new CoverageTarget().value(1));",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(testSource), generatedTestClasses,
                Arrays.asList(targetClasses.toString(), System.getProperty("java.class.path")));

        CoverageSummary summary = new CoverageSummaryReporter(config).report();

        Path summaryPath = testOutput.resolve("coverage-summary.json");
        Path textSummaryPath = testOutput.resolve("coverage-summary.txt");
        assertTrue(Files.exists(summaryPath));
        assertTrue(Files.exists(textSummaryPath));
        Path experimentSummaryPath = testOutput.resolve("experiment-summary.txt");
        assertTrue(Files.exists(experimentSummaryPath));
        assertEquals(1, summary.getTargetClassCount());
        assertEquals(1, summary.getTestClassCount());
        assertEquals(1, summary.getTestsFound());
        assertEquals(0, summary.getTestsFailed());
        assertEquals(1, summary.getBranchCovered());
        assertEquals(2, summary.getBranchTotal());
        assertEquals(50.0, summary.getBranchCoverage());
        assertEquals(0, summary.getFullyCoveredMethodCount());
        assertTrue(summary.getLineCoverage() > 0.0);
        assertTrue(new Gson().fromJson(readString(summaryPath), CoverageSummary.class).getLineCoverage() > 0.0);
        assertTrue(readString(textSummaryPath).contains("Line Coverage:"));
        String experimentSummary = readString(experimentSummaryPath);
        assertTrue(experimentSummary.contains("ChatUniTest Experiment Summary"));
        assertTrue(experimentSummary.contains("Achieved Coverage"));
        assertTrue(experimentSummary.contains("Line Coverage:"));
        assertTrue(experimentSummary.contains("Fully covered methods: 0"));
        assertTrue(experimentSummary.contains("LLM attempts: 2"));
        assertTrue(experimentSummary.contains("LLM successes: 1"));
        assertFalse(experimentSummary.contains("Timing Profile"));
        assertFalse(experimentSummary.contains("count="));
        assertTrue(experimentSummary.contains("Output: " + testOutput));
    }

    @Test
    void reporterSkipsTestMergedSuiteDirectoryWhenDiscoveringGeneratedTests() throws Exception {
        Path projectDir = tempDir.resolve("project-merged-skip");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-merged-skip-tests");
        Path tmpOutput = tempDir.resolve("tmp-merged-skip");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));
        Files.createDirectories(testOutput.resolve("test_merged/example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        if (input > 0) {",
                "            return 1;",
                "        }",
                "        return -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .cname("example.CoverageTarget")
                .build();

        Path testSource = testOutput.resolve("example/CoverageTarget_0_Test.java");
        Files.write(testSource, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTarget_0_Test {",
                "    @Test",
                "    void coversPositiveBranch() {",
                "        assertEquals(1, new CoverageTarget().value(1));",
                "    }",
                "}"), StandardCharsets.UTF_8);
        Files.write(testOutput.resolve("test_merged/example/CoverageTargetMergedTest.java"), Arrays.asList(
                "package example;",
                "public class CoverageTargetMergedTest {",
                "    void broken( {",
                "}"), StandardCharsets.UTF_8);

        CoverageSummary summary = new CoverageSummaryReporter(config).report();

        assertEquals(1, summary.getTestClassCount());
        assertEquals(1, summary.getTestsFound());
        assertEquals(1, summary.getTestsSucceeded());
        assertEquals(0, summary.getTestsFailed());
        assertTrue(readString(testOutput.resolve("coverage-invalid-tests.txt"))
                .contains("No invalid coverage test sources skipped."));
    }

    @Test
    void reporterCountsBothBranchesWhenGeneratedTestsExerciseBothPaths() throws Exception {
        Path projectDir = tempDir.resolve("project-both-branches");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-both-branch-tests");
        Path tmpOutput = tempDir.resolve("tmp-both-branches");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        if (input > 0) {",
                "            return 1;",
                "        }",
                "        return -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .cname("example.CoverageTarget")
                .build();

        Path testSource = testOutput.resolve("example/CoverageTargetBothBranchesTest.java");
        Files.write(testSource, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetBothBranchesTest {",
                "    @Test",
                "    void coversPositiveBranch() {",
                "        assertEquals(1, new CoverageTarget().value(1));",
                "    }",
                "    @Test",
                "    void coversNegativeBranch() {",
                "        assertEquals(-1, new CoverageTarget().value(-1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        CoverageSummary summary = new CoverageSummaryReporter(config).report();

        assertEquals(2, summary.getTestsFound());
        assertEquals(2, summary.getTestsSucceeded());
        assertEquals(2, summary.getBranchCovered());
        assertEquals(2, summary.getBranchTotal());
        assertEquals(100.0, summary.getBranchCoverage());
        assertEquals(1, summary.getFullyCoveredMethodCount());
        assertEquals(1, config.getFullyCoveredMethodCount());
        assertTrue(Files.exists(testOutput.resolve("fully_covered_methods.json")));
        String fullyCoveredMethods = readString(testOutput.resolve("fully_covered_methods.json"));
        assertTrue(fullyCoveredMethods.contains("\"className\": \"example.CoverageTarget\""));
        assertTrue(fullyCoveredMethods.contains("\"methodName\": \"value\""));
        assertTrue(fullyCoveredMethods.contains("\"lineCoverage\": 100.0"));
        assertTrue(readString(testOutput.resolve("coverage-summary.txt"))
                .contains("Branch Coverage: 100.00% (2/2)"));
    }

    @Test
    void coverageOnlyResumesFromCompletedTestClassCheckpoint() throws Exception {
        Path projectDir = tempDir.resolve("project-resume");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-resume-tests");
        Path tmpOutput = tempDir.resolve("tmp-resume");
        Path sideEffect = tempDir.resolve("resume-test-ran.txt");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        if (input > 0) {",
                "            return 1;",
                "        }",
                "        return -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .coverageOnlyMode(true)
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .cname("example.CoverageTarget")
                .build();

        String sideEffectPath = sideEffect.toString().replace("\\", "\\\\");
        Path testSource = testOutput.resolve("example/CoverageTargetResumeTest.java");
        Files.write(testSource, Arrays.asList(
                "package example;",
                "import java.nio.charset.StandardCharsets;",
                "import java.nio.file.Files;",
                "import java.nio.file.Paths;",
                "import java.util.Collections;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetResumeTest {",
                "    @Test",
                "    void coversPositiveBranch() throws Exception {",
                "        Files.write(Paths.get(\"" + sideEffectPath + "\"), Collections.singletonList(\"ran\"), StandardCharsets.UTF_8);",
                "        assertEquals(1, new CoverageTarget().value(1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        CoverageSummary first = new CoverageSummaryReporter(config).report();
        assertTrue(Files.exists(sideEffect));
        assertEquals(1, first.getTestsSucceeded());
        assertEquals(50.0, first.getBranchCoverage());

        Files.delete(sideEffect);
        CoverageSummary second = new CoverageSummaryReporter(config).report();

        assertFalse(Files.exists(sideEffect));
        assertEquals(1, second.getTestClassCount());
        assertEquals(1, second.getTestsFound());
        assertEquals(1, second.getTestsSucceeded());
        assertEquals(0, second.getTestsFailed());
        assertEquals(50.0, second.getBranchCoverage());
    }

    @Test
    void reporterMeasuresFinalCoverageForIndividuallyValidGeneratedTestsWithDuplicateHelpers() throws Exception {
        Path projectDir = tempDir.resolve("project-duplicate-helpers");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-duplicate-helper-tests");
        Path tmpOutput = tempDir.resolve("tmp-duplicate-helpers");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        if (input > 0) {",
                "            return 1;",
                "        }",
                "        return -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .cname("example.CoverageTarget")
                .build();

        Path positiveTest = testOutput.resolve("example/CoverageTargetPositiveTest.java");
        Files.write(positiveTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetPositiveTest {",
                "    @Test",
                "    void coversPositiveBranch() {",
                "        assertEquals(1, new CoverageTarget().value(new Helper().input()));",
                "    }",
                "}",
                "class Helper {",
                "    int input() { return 1; }",
                "}"), StandardCharsets.UTF_8);

        Path negativeTest = testOutput.resolve("example/CoverageTargetNegativeTest.java");
        Files.write(negativeTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetNegativeTest {",
                "    @Test",
                "    void coversNegativeBranch() {",
                "        assertEquals(-1, new CoverageTarget().value(new Helper().input()));",
                "    }",
                "}",
                "class Helper {",
                "    int input() { return -1; }",
                "}"), StandardCharsets.UTF_8);

        CoverageSummary summary = new CoverageSummaryReporter(config).report();

        assertEquals(2, summary.getTestsFound());
        assertEquals(2, summary.getTestsSucceeded());
        assertEquals(0, summary.getTestsFailed());
        assertEquals(2, summary.getBranchCovered());
        assertEquals(2, summary.getBranchTotal());
        assertEquals(100.0, summary.getBranchCoverage());
    }

    @Test
    void reporterBatchCompilesNonConflictingGeneratedTests() throws Exception {
        Path projectDir = tempDir.resolve("project-batched-tests");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-batched-tests");
        Path tmpOutput = tempDir.resolve("tmp-batched-tests");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        if (input > 0) {",
                "            return 1;",
                "        }",
                "        return -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .cname("example.CoverageTarget")
                .build();

        Path positiveTest = testOutput.resolve("example/CoverageTargetPositiveTest.java");
        Files.write(positiveTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetPositiveTest {",
                "    @Test",
                "    void coversPositiveBranch() {",
                "        assertEquals(1, new CoverageTarget().value(new PositiveInput().input()));",
                "    }",
                "}",
                "class PositiveInput {",
                "    int input() { return 1; }",
                "}"), StandardCharsets.UTF_8);

        Path negativeTest = testOutput.resolve("example/CoverageTargetNegativeTest.java");
        Files.write(negativeTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetNegativeTest {",
                "    @Test",
                "    void coversNegativeBranch() {",
                "        assertEquals(-1, new CoverageTarget().value(new NegativeInput().input()));",
                "    }",
                "}",
                "class NegativeInput {",
                "    int input() { return -1; }",
                "}"), StandardCharsets.UTF_8);

        CoverageSummary summary = new CoverageSummaryReporter(config).report();

        assertEquals(2, summary.getTestClassCount());
        assertEquals(2, summary.getTestsFound());
        Path batchedRoot = config.getCompileOutputPath().resolve("__coverage-tests");
        try (java.util.stream.Stream<Path> batches = Files.list(batchedRoot)) {
            assertEquals(1, batches.filter(Files::isDirectory).count());
        }
    }

    @Test
    void reporterUpdatesProgressWhileRunningCoverageTests() throws Exception {
        Path projectDir = tempDir.resolve("project-coverage-progress");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-progress-tests");
        Path tmpOutput = tempDir.resolve("tmp-progress-tests");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        return input > 0 ? 1 : -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .coverageOnlyMode(true)
                .cname("example.CoverageTarget")
                .build();

        Path positiveTest = testOutput.resolve("example/CoverageTargetPositiveTest.java");
        Files.write(positiveTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetPositiveTest {",
                "    @Test",
                "    void coversPositiveBranch() {",
                "        assertEquals(1, new CoverageTarget().value(1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        Path negativeTest = testOutput.resolve("example/CoverageTargetNegativeTest.java");
        Files.write(negativeTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetNegativeTest {",
                "    @Test",
                "    void coversNegativeBranch() {",
                "        assertEquals(-1, new CoverageTarget().value(-1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        new CoverageSummaryReporter(config).report();

        assertEquals(2, config.getJobCount().get());
        assertEquals(2, config.getCompletedJobCount().get());
        assertEquals("Coverage", config.getCurrentStatusStep());
    }

    @Test
    void coverageOnlyProgressCountsValidCompileAndRuntimeFailures() throws Exception {
        Path projectDir = tempDir.resolve("project-coverage-only-counts");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-coverage-count-tests");
        Path tmpOutput = tempDir.resolve("tmp-coverage-count-tests");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        return input > 0 ? 1 : -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .coverageOnlyMode(true)
                .cname("example.CoverageTarget")
                .build();

        Path passingTest = testOutput.resolve("example/CoverageTargetPassingTest.java");
        Files.write(passingTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetPassingTest {",
                "    @Test",
                "    void passes() {",
                "        assertEquals(1, new CoverageTarget().value(1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        Path failingTest = testOutput.resolve("example/CoverageTargetFailingTest.java");
        Files.write(failingTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetFailingTest {",
                "    @Test",
                "    void failsAtRuntime() {",
                "        assertEquals(99, new CoverageTarget().value(-1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        Path invalidTest = testOutput.resolve("example/CoverageTargetInvalidTest.java");
        Files.write(invalidTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "public class CoverageTargetInvalidTest {",
                "    @Test",
                "    void doesNotCompile() {",
                "        int broken =",
                "    }",
                "}"), StandardCharsets.UTF_8);

        CoverageSummary summary = new CoverageSummaryReporter(config).report();

        assertEquals(2, summary.getTestsFound());
        assertEquals(1, summary.getTestsSucceeded());
        assertEquals(1, summary.getTestsFailed());
        assertEquals(1, config.getValidCoverageTestCount().get());
        assertEquals(1, config.getCompilationErrorCount().get());
        assertEquals(1, config.getRuntimeErrorCount().get());
        assertEquals(2, config.getJobCount().get());
        assertEquals(2, config.getCompletedJobCount().get());
    }

    @Test
    void coverageExecutionTimesOutEachTestClassIndependently() throws Exception {
        Path projectDir = tempDir.resolve("project-coverage-timeout");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-timeout-tests");
        Path tmpOutput = tempDir.resolve("tmp-timeout-tests");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        return input > 0 ? 1 : -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .coverageOnlyMode(true)
                .cname("example.CoverageTarget")
                .build();

        Path passingTest = testOutput.resolve("example/CoverageTargetFastTest.java");
        Files.write(passingTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetFastTest {",
                "    @Test",
                "    void passes() {",
                "        assertEquals(1, new CoverageTarget().value(1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        Path slowTest = testOutput.resolve("example/CoverageTargetSlowTest.java");
        Files.write(slowTest, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "public class CoverageTargetSlowTest {",
                "    @Test",
                "    void timesOut() throws Exception {",
                "        Thread.sleep(500);",
                "    }",
                "}"), StandardCharsets.UTF_8);

        CoverageSummary summary = new CoverageSummaryReporter(config, 50, TimeUnit.MILLISECONDS).report();

        assertEquals(2, summary.getTestsFound());
        assertEquals(1, summary.getTestsSucceeded());
        assertEquals(1, summary.getTestsFailed());
        assertEquals(1, config.getValidCoverageTestCount().get());
        assertEquals(1, config.getRuntimeErrorCount().get());
        assertEquals(1, config.getTimeoutCount().get());
        assertEquals(2, config.getCompletedJobCount().get());
    }

    @Test
    void reporterIgnoresCompiledCandidatesThatAreNotFinalOutputTests() throws Exception {
        Path projectDir = tempDir.resolve("project-final-only");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("generated-final-tests");
        Path tmpOutput = tempDir.resolve("tmp-final-only");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        if (input > 0) {",
                "            return 1;",
                "        }",
                "        return -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .cname("example.CoverageTarget")
                .build();
        Path generatedTestClasses = config.getCompileOutputPath();
        Files.createDirectories(generatedTestClasses);

        Path acceptedTestSource = testOutput.resolve("example/CoverageTarget_Accepted_Test.java");
        Files.write(acceptedTestSource, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTarget_Accepted_Test {",
                "    @Test",
                "    void coversPositiveBranch() {",
                "        assertEquals(1, new CoverageTarget().value(1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        Path candidateSourceRoot = tempDir.resolve("discarded-candidate");
        Path discardedCandidateSource = candidateSourceRoot.resolve("example/CoverageTarget_Discarded_Test.java");
        Files.createDirectories(discardedCandidateSource.getParent());
        Files.write(discardedCandidateSource, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTarget_Discarded_Test {",
                "    @Test",
                "    void coversNegativeBranch() {",
                "        assertEquals(-1, new CoverageTarget().value(-1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        compile(Arrays.asList(acceptedTestSource, discardedCandidateSource), generatedTestClasses,
                Arrays.asList(targetClasses.toString(), System.getProperty("java.class.path")));

        CoverageSummary summary = new CoverageSummaryReporter(config).report();

        assertEquals(1, summary.getTestClassCount());
        assertEquals(1, summary.getTestsFound());
        assertEquals(1, summary.getTestsSucceeded());
        assertTrue(summary.getBranchCoverage() < 100.0);
    }

    @Test
    void reporterCompilesExistingTestSourcesBeforeCoverageAnalysis() throws Exception {
        Path projectDir = tempDir.resolve("project-existing-tests");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path existingTests = tempDir.resolve("existing-tests");
        Path tmpOutput = tempDir.resolve("tmp-existing-tests");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(existingTests.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        if (input > 0) {",
                "            return 1;",
                "        }",
                "        return -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(existingTests)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .cname("example.CoverageTarget")
                .build();

        Path testSource = existingTests.resolve("example/CoverageTargetExistingTest.java");
        Files.write(testSource, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetExistingTest {",
                "    @Test",
                "    void coversNegativeBranch() {",
                "        assertEquals(-1, new CoverageTarget().value(-1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        CoverageSummary summary = new CoverageSummaryReporter(config).report();

        assertTrue(Files.exists(config.getCompileOutputPath().resolve("example/CoverageTargetExistingTest.class")));
        assertEquals(1, summary.getTestClassCount());
        assertEquals(1, summary.getTestsFound());
        assertEquals(1, summary.getTestsSucceeded());
        assertTrue(Files.exists(existingTests.resolve("coverage-summary.json")));
    }

    @Test
    void reporterDeletesStaleCompiledTestsBeforeRecompilingCoverageTests() throws Exception {
        Path projectDir = tempDir.resolve("project-clean-build");
        Path sourceRoot = projectDir.resolve("src/main/java");
        Path targetClasses = projectDir.resolve("target/classes");
        Path testOutput = tempDir.resolve("clean-build-tests");
        Path tmpOutput = tempDir.resolve("tmp-clean-build");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(targetClasses);
        Files.createDirectories(testOutput.resolve("example"));

        Path targetSource = sourceRoot.resolve("example/CoverageTarget.java");
        Files.write(targetSource, Arrays.asList(
                "package example;",
                "public class CoverageTarget {",
                "    public int value(int input) {",
                "        if (input > 0) {",
                "            return 1;",
                "        }",
                "        return -1;",
                "    }",
                "}"), StandardCharsets.UTF_8);
        compile(Collections.singletonList(targetSource), targetClasses, Collections.emptyList());

        Config config = new Config.ConfigBuilder(new FakeProject(projectDir, sourceRoot, targetClasses))
                .phaseType("SCOUT")
                .testOutput(testOutput)
                .tmpOutput(tmpOutput)
                .reportCoverage(true)
                .cname("example.CoverageTarget")
                .build();

        Path staleClass = config.getCompileOutputPath().resolve("example/StaleCandidateTest.class");
        Files.createDirectories(staleClass.getParent());
        Files.write(staleClass, new byte[] {0, 1, 2, 3});

        Path testSource = testOutput.resolve("example/CoverageTargetCleanTest.java");
        Files.write(testSource, Arrays.asList(
                "package example;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "public class CoverageTargetCleanTest {",
                "    @Test",
                "    void coversPositiveBranch() {",
                "        assertEquals(1, new CoverageTarget().value(1));",
                "    }",
                "}"), StandardCharsets.UTF_8);

        CoverageSummary summary = new CoverageSummaryReporter(config).report();

        assertFalse(Files.exists(staleClass));
        assertTrue(Files.exists(config.getCompileOutputPath().resolve("example/CoverageTargetCleanTest.class")));
        assertEquals(1, summary.getTestClassCount());
        assertEquals(1, summary.getTestsFound());
    }

    private void compile(List<Path> sources, Path output, List<String> classPathEntries) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(output.toFile()));
            if (!classPathEntries.isEmpty()) {
                List<File> classPath = new ArrayList<>();
                for (String entry : classPathEntries) {
                    for (String part : entry.split(File.pathSeparator)) {
                        if (!part.trim().isEmpty()) {
                            classPath.add(new File(part));
                        }
                    }
                }
                fileManager.setLocation(StandardLocation.CLASS_PATH, classPath);
            }
            Boolean result = compiler.getTask(null, fileManager, null, null, null,
                    fileManager.getJavaFileObjectsFromFiles(toFiles(sources))).call();
            assertEquals(Boolean.TRUE, result);
        }
    }

    private List<File> toFiles(List<Path> paths) {
        List<File> files = new ArrayList<>();
        for (Path path : paths) {
            files.add(path.toFile());
        }
        return files;
    }

    private String readString(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static class FakeProject implements Project {
        private final Path basedir;
        private final Path sourceRoot;
        private final Path buildPath;

        private FakeProject(Path basedir, Path sourceRoot, Path buildPath) {
            this.basedir = basedir;
            this.sourceRoot = sourceRoot;
            this.buildPath = buildPath;
        }

        @Override
        public Project getParent() {
            return null;
        }

        @Override
        public File getBasedir() {
            return basedir.toFile();
        }

        @Override
        public String getPackaging() {
            return "jar";
        }

        @Override
        public String getGroupId() {
            return "demo";
        }

        @Override
        public String getArtifactId() {
            return "demo-artifact";
        }

        @Override
        public List<String> getCompileSourceRoots() {
            return Collections.singletonList(sourceRoot.toString());
        }

        @Override
        public Path getArtifactPath() {
            return basedir.resolve("target/demo.jar");
        }

        @Override
        public Path getBuildPath() {
            return buildPath;
        }

        @Override
        public List<String> getClassPaths() {
            return new ArrayList<>();
        }

        @Override
        public List<String> getDependencyPaths() {
            return new ArrayList<>();
        }
    }
}
