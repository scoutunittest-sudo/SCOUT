package zju.cst.aces.coverage;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CoverageSummary {
    private int targetClassCount;
    private int testClassCount;
    private long testsFound;
    private long testsSucceeded;
    private long testsFailed;
    private long instructionCovered;
    private long instructionTotal;
    private long branchCovered;
    private long branchTotal;
    private long lineCovered;
    private long lineTotal;
    private double instructionCoverage;
    private double branchCoverage;
    private double lineCoverage;
    private int fullyCoveredMethodCount;
    private List<FullyCoveredMethod> fullyCoveredMethods = new ArrayList<>();

    public static double percentage(long covered, long total) {
        if (total == 0) {
            return 100.0;
        }
        return 100.0 * covered / total;
    }
}
