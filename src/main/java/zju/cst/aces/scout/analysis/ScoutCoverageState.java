package zju.cst.aces.scout.analysis;

import zju.cst.aces.scout.state.ScoutMethodState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class ScoutCoverageState {
    private final ScoutMethodState state;

    public ScoutCoverageState(ScoutMethodState state) {
        this.state = state;
    }

    public void recordAttempt(String attemptKey, List<String> uncoveredRegions, double score, String testCode) {
        ArrayList<String> attemptRegions = new ArrayList<>(uncoveredRegions);
        state.getUncoveredRegionsByAttempt().put(attemptKey, attemptRegions);
        state.setStableUncoveredRegions(intersectAttemptRegions());

        if (score >= state.getBestCoverageScore()) {
            state.setBestCoverageScore(score);
            state.setBestTestCode(testCode == null ? "" : testCode);
        }

        if (attemptRegions.isEmpty() || score >= 300.0) {
            state.setFullyCovered(true);
        }
    }

    private ArrayList<String> intersectAttemptRegions() {
        LinkedHashSet<String> stableRegions = new LinkedHashSet<>();
        boolean initialized = false;

        for (List<String> attemptRegions : state.getUncoveredRegionsByAttempt().values()) {
            LinkedHashSet<String> attemptRegionSet = new LinkedHashSet<>(attemptRegions);
            if (!initialized) {
                stableRegions.addAll(attemptRegionSet);
                initialized = true;
            } else {
                stableRegions.retainAll(attemptRegionSet);
            }
        }

        return new ArrayList<>(stableRegions);
    }
}
