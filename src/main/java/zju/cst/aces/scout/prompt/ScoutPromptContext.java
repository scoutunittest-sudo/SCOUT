package zju.cst.aces.scout.prompt;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ScoutPromptContext {
    private ScoutPromptMode mode;
    private Map<String, Object> dataModel = new LinkedHashMap<>();
    private List<String> cachedTests = new ArrayList<>();
    private List<String> uncoveredRegions = new ArrayList<>();
    private List<String> errorMessages = new ArrayList<>();
}
