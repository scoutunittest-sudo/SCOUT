package zju.cst.aces.scout.scenario;

import lombok.Data;

import java.util.ArrayList;

@Data
public class ScoutScenarioAnalysis {
    private ArrayList<ScoutScenario> scenarios = new ArrayList<>();
    private ArrayList<String> coveredScenarioIds = new ArrayList<>();
    private ArrayList<String> uncoveredScenarioIds = new ArrayList<>();
}
