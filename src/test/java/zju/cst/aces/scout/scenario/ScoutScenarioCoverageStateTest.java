package zju.cst.aces.scout.scenario;

import org.junit.jupiter.api.Test;
import zju.cst.aces.scout.analysis.ScoutCoverageResult;
import zju.cst.aces.scout.state.ScoutMethodState;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutScenarioCoverageStateTest {
    @Test
    void treatsCoveredBranchLineWithUncoveredTrueFlagAsFalseBranchExecution() {
        ScoutScenario trueScenario = scenario("scenario-1", "large amount path", "TRUE");
        ScoutScenario falseScenario = scenario("scenario-2", "small amount path", "FALSE");
        ScoutMethodState state = new ScoutMethodState();
        state.getScenarios().add(trueScenario);
        state.getScenarios().add(falseScenario);

        ScoutCoverageResult coverage = new ScoutCoverageResult();
        coverage.getUncoveredLines().add(5);

        new ScoutScenarioCoverageState(state).recordAttempt("attempt-1", coverage);

        assertFalse(trueScenario.isCovered());
        assertTrue(falseScenario.isCovered());
    }

    @Test
    void treatsCoveredTrueFlagAsTrueBranchExecution() {
        ScoutScenario trueScenario = scenario("scenario-1", "large amount path", "TRUE");
        ScoutScenario falseScenario = scenario("scenario-2", "small amount path", "FALSE");
        ScoutMethodState state = new ScoutMethodState();
        state.getScenarios().add(trueScenario);
        state.getScenarios().add(falseScenario);

        ScoutCoverageResult coverage = new ScoutCoverageResult();
        coverage.getUncoveredLines().add(7);

        new ScoutScenarioCoverageState(state).recordAttempt("attempt-1", coverage);

        assertTrue(trueScenario.isCovered());
        assertFalse(falseScenario.isCovered());
    }

    private ScoutScenario scenario(String id, String description, String outcome) {
        ScoutScenario scenario = new ScoutScenario();
        scenario.setId(id);
        scenario.setDescription(description);
        scenario.getRelatedLines().add(4);

        ScoutBranchMapping mapping = new ScoutBranchMapping();
        mapping.setConditionLine(4);
        mapping.setTrueBranchFlagLine(5);
        mapping.setOutcome(outcome);
        scenario.getBranchMappings().add(mapping);
        return scenario;
    }
}
