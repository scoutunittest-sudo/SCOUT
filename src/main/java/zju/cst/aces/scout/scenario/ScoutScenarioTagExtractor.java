package zju.cst.aces.scout.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScoutScenarioTagExtractor {
    private static final Pattern TAG_PATTERN =
            Pattern.compile("^\\s*//\\s*SCOUT-SCENARIO:\\s*([^\\s]+)\\s*$");
    private static final Pattern METHOD_PATTERN =
            Pattern.compile(".*\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(.*");

    public List<ScoutScenarioTag> extract(String code) {
        ArrayList<ScoutScenarioTag> tags = new ArrayList<>();
        if (code == null || code.trim().isEmpty()) {
            return tags;
        }

        String pendingScenarioId = "";
        String[] lines = code.split("\\R");
        for (String line : lines) {
            Matcher tagMatcher = TAG_PATTERN.matcher(line);
            if (tagMatcher.matches()) {
                pendingScenarioId = tagMatcher.group(1).trim();
                continue;
            }
            if (pendingScenarioId.isEmpty() || shouldSkipBeforeMethod(line)) {
                continue;
            }
            String methodName = methodName(line);
            if (!methodName.isEmpty()) {
                tags.add(new ScoutScenarioTag(pendingScenarioId, methodName));
            }
            pendingScenarioId = "";
        }
        return tags;
    }

    private boolean shouldSkipBeforeMethod(String line) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("//");
    }

    private String methodName(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.contains("(") || trimmed.startsWith("if ") || trimmed.startsWith("for ")
                || trimmed.startsWith("while ") || trimmed.startsWith("switch ")) {
            return "";
        }
        Matcher matcher = METHOD_PATTERN.matcher(trimmed);
        return matcher.matches() ? matcher.group(1) : "";
    }
}
