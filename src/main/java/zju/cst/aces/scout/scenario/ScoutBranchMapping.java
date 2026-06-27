package zju.cst.aces.scout.scenario;

import lombok.Data;

import java.util.ArrayList;

@Data
public class ScoutBranchMapping {
    public static final String TRUE = "TRUE";
    public static final String FALSE = "FALSE";
    public static final String UNKNOWN = "UNKNOWN";

    private String branchText = "";
    private int conditionLine = -1;
    private int trueBranchFlagLine = -1;
    private String outcome = UNKNOWN;
    private ArrayList<String> parameterDependencies = new ArrayList<>();
    private ArrayList<String> fieldDependencies = new ArrayList<>();
    private ArrayList<String> callDependencies = new ArrayList<>();
}
