package zju.cst.aces.scout.state;

import lombok.Data;
import zju.cst.aces.scout.scenario.ScoutScenario;
import zju.cst.aces.scout.scenario.ScoutScenarioTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Data
public class ScoutMethodState {
    private String fullClassName = "";
    private String methodSignature = "";
    private String methodSummary = "";
    private String scenario = "";
    private boolean fullyCovered;
    private double bestCoverageScore;
    private String bestTestCode = "";
    private LinkedHashMap<String, List<String>> uncoveredRegionsByAttempt = new LinkedHashMap<>();
    private ArrayList<String> stableUncoveredRegions = new ArrayList<>();
    private String lastPromptMode = "";
    private ArrayList<ScoutScenario> scenarios = new ArrayList<>();
    private LinkedHashMap<String, List<String>> coveredScenarioIdsByAttempt = new LinkedHashMap<>();
    private LinkedHashMap<String, List<ScoutScenarioTag>> taggedScenarioAttemptsByAttempt = new LinkedHashMap<>();
    private ArrayList<String> uncoveredScenarioIds = new ArrayList<>();
    private String scenarioGuidance = "";
}
