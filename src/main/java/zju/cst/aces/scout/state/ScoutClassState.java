package zju.cst.aces.scout.state;

import lombok.Data;

import java.util.LinkedHashMap;

@Data
public class ScoutClassState {
    private String fullClassName = "";
    private String className = "";
    private String classSummary = "";
    private String checklist = "";
    private LinkedHashMap<String, String> cachedTestsByAttempt = new LinkedHashMap<>();
    private boolean privateConstructor;
    private String constructorCalls = "";
}
