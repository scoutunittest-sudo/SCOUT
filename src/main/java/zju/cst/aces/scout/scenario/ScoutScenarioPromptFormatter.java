package zju.cst.aces.scout.scenario;

import zju.cst.aces.scout.state.ScoutMethodState;

import java.util.LinkedHashMap;
import java.util.Map;

public class ScoutScenarioPromptFormatter {
    public String formatUncoveredScenarios(ScoutMethodState state) {
        if (state == null
                || state.getUncoveredScenarioIds() == null
                || state.getUncoveredScenarioIds().isEmpty()
                || state.getScenarios() == null
                || state.getScenarios().isEmpty()) {
            return "";
        }

        Map<String, ScoutScenario> scenariosById = new LinkedHashMap<String, ScoutScenario>();
        for (ScoutScenario scenario : state.getScenarios()) {
            if (scenario != null && scenario.getId() != null && !scenario.getId().isEmpty()) {
                scenariosById.put(scenario.getId(), scenario);
            }
        }

        StringBuilder builder = new StringBuilder();
        for (String scenarioId : state.getUncoveredScenarioIds()) {
            ScoutScenario scenario = scenariosById.get(scenarioId);
            if (scenario == null) {
                continue;
            }

            if (builder.length() == 0) {
                builder.append("Target scenario cards:");
            }
            builder.append("\n- Scenario: [")
                    .append(scenario.getId())
                    .append("] ")
                    .append(nullToEmpty(scenario.getDescription()));
            appendRepairEvidence(builder, scenario);
            builder.append("\n  Expected observation: assert the externally visible expected behavior from the scenario.");
        }

        return builder.toString();
    }

    private void appendRepairEvidence(StringBuilder builder, ScoutScenario scenario) {
        String evidence = repairEvidence(scenario);
        if (!evidence.isEmpty()) {
            builder.append("\n  ")
                    .append("Relevant repair evidence")
                    .append(": ")
                    .append(evidence);
        }
    }

    private String repairEvidence(ScoutScenario scenario) {
        if (scenario == null || scenario.getBranchMappings() == null || scenario.getBranchMappings().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ScoutBranchMapping mapping : scenario.getBranchMappings()) {
            String evidence = mappingEvidence(mapping);
            if (evidence.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(evidence);
        }
        return builder.toString();
    }

    private String mappingEvidence(ScoutBranchMapping mapping) {
        if (mapping == null
                || mapping.getConditionLine() <= 0
                || mapping.getTrueBranchFlagLine() <= 0
                || ScoutBranchMapping.UNKNOWN.equals(mapping.getOutcome())) {
            return "";
        }

        String branchText = nullToEmpty(mapping.getBranchText()).trim();
        if (branchText.isEmpty()) {
            branchText = "condition";
        }
        return mapping.getOutcome()
                + " path of "
                + branchText;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
