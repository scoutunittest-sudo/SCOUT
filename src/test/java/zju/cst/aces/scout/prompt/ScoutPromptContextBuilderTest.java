package zju.cst.aces.scout.prompt;

import org.junit.jupiter.api.Test;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.dto.TestMessage;
import zju.cst.aces.scout.scenario.ScoutBranchMapping;
import zju.cst.aces.scout.scenario.ScoutScenario;
import zju.cst.aces.scout.scenario.ScoutScenarioTag;
import zju.cst.aces.scout.state.ScoutClassState;
import zju.cst.aces.scout.state.ScoutMethodState;
import zju.cst.aces.scout.state.ScoutProjectState;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScoutPromptContextBuilderTest {

    @Test
    void scenarioGuidedCoverageRepairKeepsPromptFocusedOnUncoveredScenarios() {
        PromptInfo promptInfo = new PromptInfo(false, "demo.Sample", "getValue", "getValue(int)");
        promptInfo.setRound(1);
        promptInfo.setContext("class Sample { int getValue(int amount) { return amount; } }");
        promptInfo.setUnitTest("class SampleTest { @Test void previousGeneratedTest() {} }");

        ScoutClassState classState = new ScoutClassState();
        classState.getCachedTestsByAttempt().put("covered-attempt", "class SampleTest {\n" +
                "  // SCOUT-SCENARIO: scenario-1\n" +
                "  @org.junit.jupiter.api.Test\n" +
                "  void coveredSmallAmountPath() {\n" +
                "    Dependency dependency = org.mockito.Mockito.mock(Dependency.class);\n" +
                "    Sample sample = new Sample(dependency);\n" +
                "    int value = sample.getValue(1);\n" +
                "    org.junit.jupiter.api.Assertions.assertEquals(0, value);\n" +
                "  }\n" +
                "}");
        classState.getCachedTestsByAttempt().put("attempt-1", "class SampleTest {\n" +
                "  // SCOUT-SCENARIO: scenario-2\n" +
                "  @org.junit.jupiter.api.Test\n" +
                "  void coversLargeAmountPath() {\n" +
                "    Sample sample = new Sample();\n" +
                "    int value = sample.getValue(11);\n" +
                "    org.junit.jupiter.api.Assertions.assertEquals(10, value);\n" +
                "  }\n" +
                "}");

        ScoutMethodState methodState = new ScoutMethodState();
        methodState.setScenario("small amount path\nlarge amount path");
        methodState.setScenarioGuidance("Target scenario cards:\n- Scenario: [scenario-2] large amount path");
        methodState.getStableUncoveredRegions().add("if (amount > 10)");
        methodState.getUncoveredScenarioIds().add("scenario-2");
        methodState.getTaggedScenarioAttemptsByAttempt().put("attempt-1",
                Collections.singletonList(new ScoutScenarioTag("scenario-2", "coversLargeAmountPath")));

        ScoutScenario covered = new ScoutScenario();
        covered.setId("scenario-1");
        covered.setDescription("small amount path");
        covered.setCovered(true);
        covered.getBranchMappings().add(branchMapping("amount > 10", ScoutBranchMapping.FALSE));
        ScoutScenario uncovered = new ScoutScenario();
        uncovered.setId("scenario-2");
        uncovered.setDescription("large amount path");
        uncovered.getBranchMappings().add(branchMapping("amount > 10", ScoutBranchMapping.TRUE));
        methodState.getScenarios().add(covered);
        methodState.getScenarios().add(uncovered);

        ScoutPromptContext context = new ScoutPromptContextBuilder()
                .build(promptInfo, new ScoutProjectState(), classState, methodState);

        assertEquals(ScoutPromptMode.GUIDE_COVERAGE, context.getMode());
        assertEquals("large amount path", context.getDataModel().get("scenario"));
        String cachedTests = (String) context.getDataModel().get("cached_tests");
        assertFalse(cachedTests.contains("org.junit.jupiter.api.Assertions.assertEquals"));
        assertFalse(cachedTests.contains("coveredSmallAmountPath"));
        assertEquals("if (amount > 10)", context.getDataModel().get("uncovered_regions"));
        assertEquals("class SampleTest { @Test void previousGeneratedTest() {} }", context.getDataModel().get("unit_test"));
        assertEquals("small amount path\n  Branch statement covered: FALSE branch of amount > 10",
                context.getDataModel().get("covered_scenarios"));
        assertEquals("large amount path\n  Branch statement to cover: TRUE branch of amount > 10",
                context.getDataModel().get("uncovered_scenarios"));
        assertEquals("- scenario-2 via coversLargeAmountPath: attempted, but still uncovered",
                context.getDataModel().get("tagged_scenario_attempts"));
        assertEquals("[scenario-2 via coversLargeAmountPath]\n" +
                        "// SCOUT-SCENARIO: scenario-2\n" +
                        "@org.junit.jupiter.api.Test\n" +
                        "void coversLargeAmountPath() {\n" +
                        "  Sample sample = new Sample();\n" +
                        "  int value = sample.getValue(11);\n" +
                        "  org.junit.jupiter.api.Assertions.assertEquals(10, value);\n" +
                        "}",
                context.getDataModel().get("tagged_scenario_test_examples"));
        assertFalse(((String) context.getDataModel().get("scenario")).contains("small amount path"));
        assertFalse(((String) context.getDataModel().get("tagged_scenario_test_examples")).contains("coveredSmallAmountPath"));
    }

    @Test
    void previousErrorIncludesErrorTypeBeforeErrorMessages() {
        PromptInfo promptInfo = new PromptInfo(false, "demo.Sample", "getValue", "getValue()");
        promptInfo.setRound(1);
        TestMessage errorMessage = new TestMessage();
        errorMessage.setErrorType(TestMessage.ErrorType.RUNTIME_ERROR);
        errorMessage.setErrorMessage(Collections.singletonList("Error in SampleTest: line 12 : java.lang.NullPointerException"));
        promptInfo.setErrorMsg(errorMessage);

        ScoutPromptContext context = new ScoutPromptContextBuilder()
                .build(promptInfo, new ScoutProjectState(), new ScoutClassState(), new ScoutMethodState());

        assertEquals("Error type: RUNTIME_ERROR\n" +
                        "Error in SampleTest: line 12 : java.lang.NullPointerException",
                context.getDataModel().get("error_message"));
    }

    private ScoutBranchMapping branchMapping(String branchText, String outcome) {
        ScoutBranchMapping mapping = new ScoutBranchMapping();
        mapping.setBranchText(branchText);
        mapping.setOutcome(outcome);
        mapping.setConditionLine(10);
        mapping.setTrueBranchFlagLine(11);
        return mapping;
    }
}
