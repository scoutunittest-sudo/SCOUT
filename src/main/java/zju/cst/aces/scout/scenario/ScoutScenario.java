package zju.cst.aces.scout.scenario;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ScoutScenario {
    private String id = "";
    private String title = "";
    private String description = "";
    private ArrayList<String> branchHints = new ArrayList<>();
    private ArrayList<String> argumentHints = new ArrayList<>();
    private ArrayList<String> dependencyHints = new ArrayList<>();
    private ArrayList<Integer> relatedLines = new ArrayList<>();
    private ArrayList<ScoutBranchMapping> branchMappings = new ArrayList<>();
    private boolean covered;

    public List<String> allHints() {
        ArrayList<String> hints = new ArrayList<>();
        hints.addAll(branchHints);
        hints.addAll(argumentHints);
        hints.addAll(dependencyHints);
        return hints;
    }
}
