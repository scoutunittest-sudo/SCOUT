package zju.cst.aces.scout.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.dto.ChatMessage;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.dto.TestMessage;
import zju.cst.aces.scout.agent.ScoutAgentResult;
import zju.cst.aces.scout.analysis.ScoutCoverageResult;
import zju.cst.aces.scout.analysis.ScoutUncoveredRegion;
import zju.cst.aces.scout.prompt.ScoutPromptContext;
import zju.cst.aces.scout.prompt.ScoutPromptContextBuilder;
import zju.cst.aces.scout.prompt.ScoutPromptRenderer;
import zju.cst.aces.scout.state.ScoutClassState;
import zju.cst.aces.scout.state.ScoutMethodState;
import zju.cst.aces.scout.state.ScoutStateStore;

import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutTestGenerationPromptTest {
    @TempDir
    Path tempDir;

    @Test
    void executeStoresRepairPromptWithPreviousTestErrorAndCachedSetupExamples() throws Exception {
        ScoutStateStore store = new ScoutStateStore(tempDir);
        ScoutClassState classState = new ScoutClassState();
        classState.setFullClassName("demo.Sample");
        classState.setClassName("Sample");
        classState.getCachedTestsByAttempt().put("attempt-0", "class SampleTest {\n" +
                "  private Dependency dependency = org.mockito.Mockito.mock(Dependency.class);\n" +
                "  private Sample sample;\n" +
                "  @org.junit.jupiter.api.BeforeEach\n" +
                "  void setUp() {\n" +
                "    sample = new Sample(dependency);\n" +
                "  }\n" +
                "}\n");
        store.saveClassState(classState);

        ScoutMethodState methodState = new ScoutMethodState();
        methodState.setFullClassName("demo.Sample");
        methodState.setMethodSignature("getValue()");
        methodState.setScenario("sample value path");
        methodState.getStableUncoveredRegions().add("return fallbackValue;");
        store.saveMethodState(methodState);

        PromptInfo promptInfo = new PromptInfo(false, "demo.Sample", "getValue", "getValue()");
        promptInfo.setRound(1);
        promptInfo.setContext("class Sample { int getValue() { return fallbackValue; } }");
        promptInfo.setUnitTest("class PreviousSampleTest {}");
        TestMessage errorMessage = new TestMessage();
        errorMessage.setErrorType(TestMessage.ErrorType.COMPILE_ERROR);
        errorMessage.setErrorMessage(Collections.singletonList("cannot find symbol: constructor Sample()"));
        promptInfo.setErrorMsg(errorMessage);

        PromptConstructorImpl pc = new PromptConstructorImpl(null);
        pc.setPromptInfo(promptInfo);
        ScoutPromptRenderer renderer = new ScoutPromptRenderer(null);
        ScoutTestGeneration generation = new ScoutTestGeneration(null, store,
                (classInfo, methodInfo, projectState, loadedClassState, loadedMethodState) -> {
                },
                new ScoutPromptContextBuilder(),
                new ScoutTestGeneration.PromptRenderer() {
                    @Override
                    public String renderSystem(ScoutPromptContext context) {
                        return "system";
                    }

                    @Override
                    public String renderUser(ScoutPromptContext context) {
                        return renderer.renderUser(context);
                    }
                },
                request -> new ScoutAgentResult("", 0, 0),
                (code, fullTestName, info) -> null,
                prompt -> false);

        generation.execute(pc);

        ChatMessage userPrompt = promptInfo.getRecords().get(1).getPrompt().get(1);
        assertTrue(userPrompt.getContent().contains("Dependency dependency = org.mockito.Mockito.mock(Dependency.class);"));
        assertTrue(userPrompt.getContent().contains("sample = new Sample(dependency);"));
        assertTrue(userPrompt.getContent().contains("return fallbackValue;"));
        assertTrue(userPrompt.getContent().contains("class PreviousSampleTest {}"));
        assertTrue(userPrompt.getContent().contains("Error type: COMPILE_ERROR"));
        assertTrue(userPrompt.getContent().contains("cannot find symbol: constructor Sample()"));
    }

    @Test
    void executeDoesNotWritePromptOrResponseToStdout() {
        Config config = new Config();
        ScoutTestGeneration generation = new ScoutTestGeneration(config, new ScoutStateStore(tempDir),
                (classInfo, methodInfo, projectState, loadedClassState, loadedMethodState) -> {
                },
                new ScoutPromptContextBuilder(),
                new ScoutTestGeneration.PromptRenderer() {
                    @Override
                    public String renderSystem(ScoutPromptContext context) {
                        return "system prompt";
                    }

                    @Override
                    public String renderUser(ScoutPromptContext context) {
                        return "user prompt";
                    }
                },
                request -> new ScoutAgentResult("plain agent response without code", 0, 0),
                (code, fullTestName, info) -> null,
                prompt -> false);
        PromptConstructorImpl pc = promptConstructor(0, 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(output));
            generation.execute(pc);
        } finally {
            System.setOut(originalOut);
        }

        String stdout = output.toString();
        assertFalse(stdout.contains("PROMPT CHECKING"));
        assertFalse(stdout.contains("RESPONSE"));
        assertFalse(stdout.contains("plain agent response without code"));
    }

    @Test
    void firstValidatedIncompleteScoutTestIsArchived() {
        ScoutTestGeneration generation = generationWithCoverage(coverage("if (amount > 10)"));
        PromptConstructorImpl pc = promptConstructor(0, 0);

        boolean complete = generation.recordValidatedTest(pc);

        assertFalse(complete);
        assertTrue(archivePath("Sample_getValue_0_0_round0_attempt0_Test").toFile().isFile());
    }

    @Test
    void recordValidatedTestUsesOneCoverageExecutionForAcceptedScoutTest() {
        AtomicInteger coverageExecutions = new AtomicInteger(0);
        ScoutCoverageResult coverageResult = completeCoverage();
        Config config = new Config();
        config.setTestOutput(tempDir.resolve("tests"));
        ScoutTestGeneration generation = new ScoutTestGeneration(config, new ScoutStateStore(tempDir),
                (classInfo, methodInfo, projectState, loadedClassState, loadedMethodState) -> {
                },
                new ScoutPromptContextBuilder(),
                new ScoutTestGeneration.PromptRenderer() {
                    @Override
                    public String renderSystem(ScoutPromptContext context) {
                        return "system";
                    }

                    @Override
                    public String renderUser(ScoutPromptContext context) {
                        return "user";
                    }
                },
                request -> new ScoutAgentResult("", 0, 0),
                (code, fullTestName, info) -> {
                    coverageExecutions.incrementAndGet();
                    return coverageResult;
                },
                prompt -> false);

        boolean complete = generation.recordValidatedTest(promptConstructor(0, 0));

        assertTrue(complete);
        assertEquals(1, coverageExecutions.get());
        assertTrue(tempDir.resolve("tests/demo/Sample_getValue_0_0_Test.java").toFile().isFile());
    }

    @Test
    void repeatedValidatedScoutTestWithoutNewCoverageIsArchived() throws Exception {
        ScoutStateStore store = new ScoutStateStore(tempDir);
        ScoutMethodState state = new ScoutMethodState();
        state.setFullClassName("demo.Sample");
        state.setMethodSignature("getValue()");
        state.getUncoveredRegionsByAttempt().put("method-getValue()-attempt-0-round-0",
                Collections.singletonList("if (amount > 10)"));
        state.getStableUncoveredRegions().add("if (amount > 10)");
        store.saveMethodState(state);

        ScoutTestGeneration generation = generationWithCoverage(store, coverage("if (amount > 10)"));
        PromptConstructorImpl pc = promptConstructor(1, 1);

        boolean complete = generation.recordValidatedTest(pc);

        assertFalse(complete);
        assertTrue(archivePath("Sample_getValue_0_1_round1_attempt1_Test").toFile().isFile());
    }

    @Test
    void validatedScoutTestThatCoversKnownUncoveredRegionIsArchived() throws Exception {
        ScoutStateStore store = new ScoutStateStore(tempDir);
        ScoutMethodState state = new ScoutMethodState();
        state.setFullClassName("demo.Sample");
        state.setMethodSignature("getValue()");
        state.getUncoveredRegionsByAttempt().put("method-getValue()-attempt-0-round-0",
                java.util.Arrays.asList("if (amount > 10)", "return fallbackValue;"));
        state.getStableUncoveredRegions().add("if (amount > 10)");
        state.getStableUncoveredRegions().add("return fallbackValue;");
        store.saveMethodState(state);

        ScoutTestGeneration generation = generationWithCoverage(store, coverage("return fallbackValue;"));
        PromptConstructorImpl pc = promptConstructor(1, 1);

        boolean complete = generation.recordValidatedTest(pc);

        assertFalse(complete);
        assertTrue(archivePath("Sample_getValue_0_1_round1_attempt1_Test").toFile().isFile());
    }

    @Test
    void exportedCompleteScoutTestsCountDistinctMethodsEvenWhenMethodInfoIsMissing() {
        ScoutTestGeneration generation = generationWithCoverage(completeCoverage());

        boolean firstComplete = generation.recordValidatedTest(promptConstructor(0, 0, "getValue()"));
        boolean secondComplete = generation.recordValidatedTest(promptConstructor(0, 1, "size()"));

        assertTrue(firstComplete);
        assertTrue(secondComplete);
        assertEquals(2, config(generation).getExportedTestCount().get());
        assertEquals(2, config(generation).getValidUnitTestMethodCount().get());
    }

    private ScoutTestGeneration generationWithCoverage(ScoutCoverageResult coverageResult) {
        return generationWithCoverage(new ScoutStateStore(tempDir), coverageResult);
    }

    private ScoutTestGeneration generationWithCoverage(ScoutStateStore store, ScoutCoverageResult coverageResult) {
        Config config = new Config();
        config.setTestOutput(tempDir.resolve("tests"));
        return new ScoutTestGeneration(config, store,
                (classInfo, methodInfo, projectState, loadedClassState, loadedMethodState) -> {
                },
                new ScoutPromptContextBuilder(),
                new ScoutTestGeneration.PromptRenderer() {
                    @Override
                    public String renderSystem(ScoutPromptContext context) {
                        return "system";
                    }

                    @Override
                    public String renderUser(ScoutPromptContext context) {
                        return "user";
                    }
                },
                request -> new ScoutAgentResult("", 0, 0),
                (code, fullTestName, info) -> coverageResult,
                prompt -> false);
    }

    private PromptConstructorImpl promptConstructor(int round, int attempt) {
        return promptConstructor(round, attempt, "getValue()");
    }

    private PromptConstructorImpl promptConstructor(int round, int attempt, String methodSignature) {
        PromptInfo promptInfo = new PromptInfo(false, "demo.Sample", "getValue", "getValue()");
        promptInfo.setMethodSignature(methodSignature);
        promptInfo.setRound(round);
        promptInfo.setTestNum(attempt);
        promptInfo.setUnitTest("package demo;\nclass Sample_getValue_0_" + attempt + "_Test {}\n");
        promptInfo.setTestPath(tempDir.resolve("tests/demo/Sample_getValue_0_" + attempt + "_Test.java"));

        PromptConstructorImpl pc = new PromptConstructorImpl(null);
        pc.setPromptInfo(promptInfo);
        pc.setFullTestName("demo.Sample_getValue_0_" + attempt + "_Test");
        return pc;
    }

    private Config config(ScoutTestGeneration generation) {
        try {
            java.lang.reflect.Field field = ScoutTestGeneration.class.getDeclaredField("config");
            field.setAccessible(true);
            return (Config) field.get(generation);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private ScoutCoverageResult coverage(String uncoveredCode) {
        ScoutCoverageResult result = new ScoutCoverageResult();
        result.setInstructionCoverage(80.0);
        result.setBranchCoverage(50.0);
        result.setLineCoverage(80.0);
        result.getUncoveredRegions().add(new ScoutUncoveredRegion(10, "branch", uncoveredCode));
        return result;
    }

    private ScoutCoverageResult completeCoverage() {
        ScoutCoverageResult result = new ScoutCoverageResult();
        result.setInstructionCoverage(100.0);
        result.setBranchCoverage(100.0);
        result.setLineCoverage(100.0);
        return result;
    }

    private Path archivePath(String className) {
        return tempDir.resolve("tests/demo/" + className + ".java");
    }
}
