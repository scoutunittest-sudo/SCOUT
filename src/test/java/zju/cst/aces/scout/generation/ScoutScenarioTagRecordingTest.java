package zju.cst.aces.scout.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.scout.analysis.ScoutCoverageResult;
import zju.cst.aces.scout.scenario.ScoutScenarioTag;
import zju.cst.aces.scout.state.ScoutMethodState;
import zju.cst.aces.scout.state.ScoutStateStore;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoutScenarioTagRecordingTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsTaggedScenarioAttemptsByAttemptKey() throws Exception {
        ScoutStateStore store = new ScoutStateStore(tempDir);
        PromptInfo promptInfo = new PromptInfo(false, "demo.Sample", "getValue", "getValue()");
        promptInfo.setRound(1);
        promptInfo.setTestNum(7);
        promptInfo.setUnitTest("class SampleTest {\n" +
                "  // SCOUT-SCENARIO: scenario-2\n" +
                "  @org.junit.jupiter.api.Test\n" +
                "  void coversLargeAmountPath() {}\n" +
                "}\n");

        PromptConstructorImpl pc = new PromptConstructorImpl(null);
        pc.setPromptInfo(promptInfo);

        ScoutCoverageResult coverageResult = new ScoutCoverageResult();
        ScoutTestGeneration generation = new ScoutTestGeneration(null, store,
                (classInfo, methodInfo, projectState, classState, methodState) -> {
                },
                null,
                null,
                null,
                (code, fullTestName, info) -> coverageResult,
                prompt -> false);

        generation.recordValidatedTest(pc);

        ScoutMethodState state = store.loadMethodState("demo.Sample", "getValue()");
        List<ScoutScenarioTag> tags = state.getTaggedScenarioAttemptsByAttempt()
                .get("method-getValue()-attempt-7-round-1");
        assertEquals(1, tags.size());
        assertEquals("scenario-2", tags.get(0).getScenarioId());
        assertEquals("coversLargeAmountPath", tags.get(0).getMethodName());
    }
}
