package zju.cst.aces.scout.scenario;

import org.junit.jupiter.api.Test;
import zju.cst.aces.scout.state.ScoutMethodState;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoutScenarioPromptFormatterTest {
    @Test
    void formatsOnlyUncoveredScenariosAsTargetCardsWithHints() {
        ScoutMethodState state = new ScoutMethodState();
        ScoutScenario uncovered = new ScoutScenario();
        uncovered.setId("scenario-1");
        uncovered.setDescription("large amount path");
        uncovered.getBranchHints().add("amount > 10");
        uncovered.getArgumentHints().add("amount");
        uncovered.getDependencyHints().add("helper.large");
        ScoutBranchMapping branchMapping = new ScoutBranchMapping();
        branchMapping.setBranchText("amount > 10");
        branchMapping.setConditionLine(4);
        branchMapping.setTrueBranchFlagLine(5);
        branchMapping.setOutcome(ScoutBranchMapping.TRUE);
        uncovered.getBranchMappings().add(branchMapping);

        ScoutScenario covered = new ScoutScenario();
        covered.setId("scenario-2");
        covered.setDescription("small amount path");

        state.getScenarios().add(uncovered);
        state.getScenarios().add(covered);
        state.getUncoveredScenarioIds().add("scenario-1");

        String formatted = new ScoutScenarioPromptFormatter().formatUncoveredScenarios(state);

        assertEquals("Target scenario cards:\n" +
                "- Scenario: [scenario-1] large amount path\n" +
                "  Required branch: amount > 10\n" +
                "  Branch coverage target: TRUE branch of amount > 10\n" +
                "  Required arguments: amount\n" +
                "  Dependencies to control: helper.large\n" +
                "  Expected observation: execute this scenario and assert the externally visible behavior.", formatted);
    }

    @Test
    void returnsEmptyStringWhenThereAreNoUncoveredScenarios() {
        assertEquals("", new ScoutScenarioPromptFormatter().formatUncoveredScenarios(new ScoutMethodState()));
    }
}
