package zju.cst.aces.scout.analysis;

import lombok.Data;
import zju.cst.aces.dto.TestMessage;

import java.util.ArrayList;
import java.util.List;

@Data
public class ScoutCoverageResult {
    private double instructionCoverage;
    private double branchCoverage;
    private double lineCoverage;
    private List<Integer> uncoveredLines = new ArrayList<>();
    private List<ScoutUncoveredRegion> uncoveredRegions = new ArrayList<>();
    private String methodCode = "";
    private boolean validTest = true;
    private TestMessage validationError;

    public double score() {
        return instructionCoverage + branchCoverage + lineCoverage;
    }

    public boolean isComplete() {
        return lineCoverage >= 100.0 && (branchCoverage >= 100.0 || branchCoverage < 0.0);
    }
}
