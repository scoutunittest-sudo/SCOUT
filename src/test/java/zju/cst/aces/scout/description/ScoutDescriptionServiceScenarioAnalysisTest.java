package zju.cst.aces.scout.description;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.scout.scenario.ScoutBranchMapping;
import zju.cst.aces.scout.scenario.ScoutScenario;
import zju.cst.aces.scout.agent.ScoutAgentResult;
import zju.cst.aces.scout.state.ScoutClassState;
import zju.cst.aces.scout.state.ScoutMethodState;
import zju.cst.aces.scout.state.ScoutProjectState;
import zju.cst.aces.scout.state.ScoutStateStore;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutDescriptionServiceScenarioAnalysisTest {
    @TempDir
    Path tempDir;

    @Test
    void mergeScenarioAnalysisPreservesBranchMappingsAndControlDependencies() {
        MethodInfo methodInfo = new MethodInfo(
                "Demo",
                "classify",
                "",
                "classify(int)",
                "int classify(int amount) {\n" +
                        "  int adjusted = amount + 1;\n" +
                        "  if (adjusted > 10 && enabled) {\n" +
                        "    return 1;\n" +
                        "  }\n" +
                        "  return 0;\n" +
                        "}",
                Collections.singletonList("int amount"),
                Collections.emptyMap(),
                "",
                "",
                "");

        ScoutMethodState methodState = new ScoutMethodState();
        methodState.setMethodSummary("classifies amount");
        methodState.setScenario("- large enabled amount path");
        ScoutScenario existing = new ScoutScenario();
        existing.setId("scenario-1");
        existing.setDescription("large enabled amount path");
        methodState.getScenarios().add(existing);

        ScoutDescriptionService service = new ScoutDescriptionService(null,
                new ScoutStateStore(tempDir),
                request -> new ScoutAgentResult("", 0, 0));

        service.ensureMethodDescription(methodInfo, new ScoutProjectState(), new ScoutClassState(), methodState);

        ScoutScenario merged = methodState.getScenarios().get(0);
        assertFalse(merged.getBranchMappings().isEmpty());
        ScoutBranchMapping mapping = merged.getBranchMappings().get(0);
        assertTrue(mapping.getParameterDependencies().contains("amount"));
        assertTrue(mapping.getFieldDependencies().contains("enabled"));
    }
}
