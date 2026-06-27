package zju.cst.aces.scout.scenario;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutScenarioAnalyzerTest {
    @Test
    void recordsTrueBranchFlagLineAndExpectedOutcomeForIfBranch() {
        String source = "package demo;\n" +
                "class Demo {\n" +
                "  int classify(int amount) {\n" +
                "    if (amount > 10) {\n" +
                "      return 1;\n" +
                "    }\n" +
                "    return 0;\n" +
                "  }\n" +
                "}\n";

        List<ScoutScenario> scenarios = new ScoutScenarioAnalyzer().analyze(
                "- large amount path\n" +
                        "- small amount path",
                source);

        ScoutScenario large = scenarios.get(0);
        ScoutScenario small = scenarios.get(1);

        assertFalse(large.getBranchMappings().isEmpty());
        assertEquals("TRUE", large.getBranchMappings().get(0).getOutcome());
        assertEquals(4, large.getBranchMappings().get(0).getConditionLine());
        assertEquals(5, large.getBranchMappings().get(0).getTrueBranchFlagLine());

        assertFalse(small.getBranchMappings().isEmpty());
        assertEquals("FALSE", small.getBranchMappings().get(0).getOutcome());
        assertEquals(4, small.getBranchMappings().get(0).getConditionLine());
        assertEquals(5, small.getBranchMappings().get(0).getTrueBranchFlagLine());
    }

    @Test
    void mapsBranchControlDependenciesFromParametersFieldsAndCalls() {
        String source = "package demo;\n" +
                "class Demo {\n" +
                "  private boolean enabled;\n" +
                "  private Helper helper;\n" +
                "  int classify(int amount) {\n" +
                "    int adjusted = amount + 1;\n" +
                "    if (adjusted > 10 && enabled && helper.isReady()) {\n" +
                "      return 1;\n" +
                "    }\n" +
                "    return 0;\n" +
                "  }\n" +
                "}\n";

        List<ScoutScenario> scenarios = new ScoutScenarioAnalyzer().analyze(
                "- large enabled ready amount path",
                source);

        ScoutScenario scenario = scenarios.get(0);

        assertTrue(scenario.getArgumentHints().contains("amount controls branch: adjusted > 10"));
        assertTrue(scenario.getDependencyHints().contains("field enabled controls branch: enabled"));
        assertTrue(scenario.getDependencyHints().contains("call helper.isReady controls branch: helper.isReady()"));
        assertFalse(scenario.getBranchMappings().isEmpty());
        assertTrue(scenario.getBranchMappings().get(0).getParameterDependencies().contains("amount"));
        assertTrue(scenario.getBranchMappings().get(0).getFieldDependencies().contains("enabled"));
        assertTrue(scenario.getBranchMappings().get(0).getCallDependencies().contains("helper.isReady"));
    }
}
