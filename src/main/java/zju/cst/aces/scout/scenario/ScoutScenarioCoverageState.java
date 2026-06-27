package zju.cst.aces.scout.scenario;

import zju.cst.aces.scout.analysis.ScoutCoverageResult;
import zju.cst.aces.scout.state.ScoutMethodState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public class ScoutScenarioCoverageState {
    private final ScoutMethodState state;

    public ScoutScenarioCoverageState(ScoutMethodState state) {
        this.state = state;
    }

    public void recordAttempt(String attemptKey, ScoutCoverageResult coverageResult) {
        if (state == null || state.getScenarios() == null || coverageResult == null) {
            return;
        }

        ensureScenarioState();

        ArrayList<String> coveredScenarioIds = new ArrayList<String>();
        ArrayList<String> uncoveredScenarioIds = new ArrayList<String>();

        if (coverageResult.isComplete()) {
            for (ScoutScenario scenario : state.getScenarios()) {
                if (scenario == null) {
                    continue;
                }
                scenario.setCovered(true);
                addId(coveredScenarioIds, scenario.getId());
            }
            state.getCoveredScenarioIdsByAttempt().put(attemptKey, coveredScenarioIds);
            state.getUncoveredScenarioIds().clear();
            state.setScenarioGuidance("");
            return;
        }

        Set<Integer> uncoveredLines = new HashSet<Integer>();
        if (coverageResult.getUncoveredLines() != null) {
            uncoveredLines.addAll(coverageResult.getUncoveredLines());
        }
        boolean hasUncoveredLineSignal = !uncoveredLines.isEmpty();

        for (ScoutScenario scenario : state.getScenarios()) {
            if (scenario == null) {
                continue;
            }

            boolean covered = isScenarioCovered(scenario, uncoveredLines, hasUncoveredLineSignal);
            scenario.setCovered(covered);

            if (covered) {
                addId(coveredScenarioIds, scenario.getId());
            } else {
                addId(uncoveredScenarioIds, scenario.getId());
            }
        }

        state.getCoveredScenarioIdsByAttempt().put(attemptKey, coveredScenarioIds);
        state.setUncoveredScenarioIds(uncoveredScenarioIds);
        state.setScenarioGuidance(new ScoutScenarioPromptFormatter().formatUncoveredScenarios(state));
    }

    private void ensureScenarioState() {
        if (state.getCoveredScenarioIdsByAttempt() == null) {
            state.setCoveredScenarioIdsByAttempt(new LinkedHashMap<String, List<String>>());
        }
        if (state.getUncoveredScenarioIds() == null) {
            state.setUncoveredScenarioIds(new ArrayList<String>());
        }
        if (state.getScenarioGuidance() == null) {
            state.setScenarioGuidance("");
        }
    }

    private boolean hasRelatedLines(ScoutScenario scenario) {
        if (scenario.getRelatedLines() == null) {
            return false;
        }
        for (Integer line : scenario.getRelatedLines()) {
            if (line != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isScenarioCovered(ScoutScenario scenario, Set<Integer> uncoveredLines, boolean hasUncoveredLineSignal) {
        BranchEvidence branchEvidence = branchEvidence(scenario, uncoveredLines);
        if (branchEvidence != null) {
            return branchEvidence.covered;
        }

        return hasRelatedLines(scenario)
                && hasUncoveredLineSignal
                && !hasUncoveredRelatedLine(scenario, uncoveredLines);
    }

    private BranchEvidence branchEvidence(ScoutScenario scenario, Set<Integer> uncoveredLines) {
        if (scenario.getBranchMappings() == null) {
            return null;
        }
        for (ScoutBranchMapping mapping : scenario.getBranchMappings()) {
            if (mapping == null || mapping.getConditionLine() <= 0 || mapping.getTrueBranchFlagLine() <= 0) {
                continue;
            }
            boolean conditionCovered = !uncoveredLines.contains(mapping.getConditionLine());
            if (!conditionCovered) {
                continue;
            }
            boolean trueBranchCovered = !uncoveredLines.contains(mapping.getTrueBranchFlagLine());
            if (ScoutBranchMapping.TRUE.equals(mapping.getOutcome())) {
                return new BranchEvidence(trueBranchCovered);
            }
            if (ScoutBranchMapping.FALSE.equals(mapping.getOutcome())) {
                return new BranchEvidence(!trueBranchCovered);
            }
        }
        return null;
    }

    private boolean hasUncoveredRelatedLine(ScoutScenario scenario, Set<Integer> uncoveredLines) {
        if (scenario.getRelatedLines() == null) {
            return false;
        }
        for (Integer line : scenario.getRelatedLines()) {
            if (line != null && uncoveredLines.contains(line)) {
                return true;
            }
        }
        return false;
    }

    private void addId(List<String> ids, String id) {
        if (id != null && !id.isEmpty()) {
            ids.add(id);
        }
    }

    private static class BranchEvidence {
        private final boolean covered;

        private BranchEvidence(boolean covered) {
            this.covered = covered;
        }
    }
}
