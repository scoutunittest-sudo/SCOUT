package zju.cst.aces.scout.prompt;

import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.dto.TestMessage;
import zju.cst.aces.scout.scenario.ScoutBranchMapping;
import zju.cst.aces.scout.scenario.ScoutScenario;
import zju.cst.aces.scout.scenario.ScoutScenarioTag;
import zju.cst.aces.scout.state.ScoutClassState;
import zju.cst.aces.scout.state.ScoutMethodState;
import zju.cst.aces.scout.state.ScoutProjectState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScoutPromptContextBuilder {
    private static final Pattern SCENARIO_TAG_PATTERN =
            Pattern.compile("^\\s*//\\s*SCOUT-SCENARIO:\\s*([^\\s]+)\\s*$");
    private static final Pattern METHOD_PATTERN =
            Pattern.compile(".*\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(.*");

    private final ScoutCachedTestFormatter cachedTestFormatter = new ScoutCachedTestFormatter();

    public ScoutPromptContext build(PromptInfo promptInfo,
                                    ScoutProjectState projectState,
                                    ScoutClassState classState,
                                    ScoutMethodState methodState) {
        ScoutPromptContext context = new ScoutPromptContext();

        List<String> cachedTests = cachedTests(classState);
        List<String> uncoveredRegions = uncoveredRegions(methodState);
        List<String> errorMessages = errorMessages(promptInfo);
        String scenarioGuidance = scenarioGuidance(methodState);

        context.setCachedTests(cachedTests);
        context.setUncoveredRegions(uncoveredRegions);
        context.setErrorMessages(errorMessages);

        ScoutPromptMode mode = selectMode(promptInfo, classState, cachedTests, uncoveredRegions, errorMessages, scenarioGuidance);
        context.setMode(mode);
        fillDataModel(context, promptInfo, projectState, classState, methodState, scenarioGuidance);

        if (methodState != null) {
            methodState.setLastPromptMode(mode.name());
        }

        return context;
    }

    private ScoutPromptMode selectMode(PromptInfo promptInfo,
                                       ScoutClassState classState,
                                       List<String> cachedTests,
                                       List<String> uncoveredRegions,
                                       List<String> errorMessages,
                                       String scenarioGuidance) {
        boolean hasDependencies = promptInfo != null && promptInfo.isHasDep();
        boolean constructorMode = hasConstructorSetup(classState);

        if (hasError(promptInfo)) {
            return constructorMode ? ScoutPromptMode.ERROR_CONSTR : ScoutPromptMode.ERROR;
        }
        if ((!uncoveredRegions.isEmpty() || !safe(scenarioGuidance).isEmpty()) && round(promptInfo) > 0) {
            return ScoutPromptMode.GUIDE_COVERAGE;
        }
        if (!cachedTests.isEmpty()) {
            return hasDependencies ? ScoutPromptMode.CACHE_WITH_DEPS : ScoutPromptMode.CACHE_NO_DEPS;
        }
        if (constructorMode) {
            return hasDependencies ? ScoutPromptMode.INITIAL_CONSTR_WITH_DEPS : ScoutPromptMode.INITIAL_CONSTR_NO_DEPS;
        }
        return hasDependencies ? ScoutPromptMode.INITIAL_WITH_DEPS : ScoutPromptMode.INITIAL_NO_DEPS;
    }

    private void fillDataModel(ScoutPromptContext context,
                               PromptInfo promptInfo,
                               ScoutProjectState projectState,
                               ScoutClassState classState,
                               ScoutMethodState methodState,
                               String scenarioGuidance) {
        context.getDataModel().put("project_summary", projectState == null ? "" : safe(projectState.getProjectSummary()));
        context.getDataModel().put("class_summary", classState == null ? "" : safe(classState.getClassSummary()));
        context.getDataModel().put("checklist", classState == null ? "" : safe(classState.getChecklist()));
        context.getDataModel().put("method_summary", methodState == null ? "" : safe(methodState.getMethodSummary()));
        boolean scenarioGuidedCoverage = isScenarioGuidedCoverage(context, scenarioGuidance);
        context.getDataModel().put("scenario",
                scenarioGuidedCoverage ? uncoveredScenarioDescriptions(methodState)
                        : methodState == null ? "" : safe(methodState.getScenario()));
        context.getDataModel().put("class_name", promptInfo == null ? "" : safe(promptInfo.getClassName()));
        context.getDataModel().put("full_class_name", promptInfo == null ? "" : safe(promptInfo.getFullClassName()));
        context.getDataModel().put("method_name", promptInfo == null ? "" : safe(promptInfo.getMethodName()));
        context.getDataModel().put("method_sig", promptInfo == null ? "" : safe(promptInfo.getMethodSignature()));
        context.getDataModel().put("information", promptInfo == null ? "" : safe(promptInfo.getContext()));
        context.getDataModel().put("other_method_sigs", promptInfo == null ? "" : safe(promptInfo.getOtherMethodBrief()));
        context.getDataModel().put("constructor_calls", classState == null ? "" : safe(classState.getConstructorCalls()));
        context.getDataModel().put("cached_tests", joinLines(context.getCachedTests()));
        context.getDataModel().put("uncovered_regions", joinLines(context.getUncoveredRegions()));
        context.getDataModel().put("error_message", joinLines(context.getErrorMessages()));
        context.getDataModel().put("scenario_guidance", safe(scenarioGuidance));
        context.getDataModel().put("uncovered_scenarios", uncoveredScenarioStatusDescriptions(methodState));
        context.getDataModel().put("covered_scenarios", coveredScenarioDescriptions(methodState));
        context.getDataModel().put("tagged_scenario_attempts", taggedScenarioAttempts(methodState));
        context.getDataModel().put("tagged_scenario_test_examples",
                taggedScenarioTestExamples(classState, methodState));
        context.getDataModel().put("unit_test", promptInfo == null ? "" : safe(promptInfo.getUnitTest()));
        context.getDataModel().put("constructor_deps", constructorDeps(promptInfo));
        context.getDataModel().put("method_deps", methodDeps(promptInfo));
    }

    private List<String> cachedTests(ScoutClassState classState) {
        if (classState == null || classState.getCachedTestsByAttempt() == null) {
            return new ArrayList<>();
        }
        ArrayList<String> snippets = new ArrayList<>();
        String formatted = cachedTestFormatter.format(new ArrayList<>(classState.getCachedTestsByAttempt().values()));
        if (!formatted.isEmpty()) {
            snippets.add(formatted);
        }
        return snippets;
    }

    private List<String> uncoveredRegions(ScoutMethodState methodState) {
        ArrayList<String> regions = new ArrayList<>();
        if (methodState == null) {
            return regions;
        }
        if (methodState.getStableUncoveredRegions() != null) {
            regions.addAll(methodState.getStableUncoveredRegions());
        }
        return regions;
    }

    private List<String> errorMessages(PromptInfo promptInfo) {
        if (promptInfo == null) {
            return new ArrayList<>();
        }
        TestMessage errorMsg = promptInfo.getErrorMsg();
        if (errorMsg == null) {
            return new ArrayList<>();
        }
        ArrayList<String> errors = new ArrayList<>();
        if (errorMsg.getErrorType() != null) {
            errors.add("Error type: " + errorMsg.getErrorType().name());
        }
        if (errorMsg.getErrorMessage() != null) {
            errors.addAll(errorMsg.getErrorMessage());
        }
        return errors;
    }

    private boolean hasError(PromptInfo promptInfo) {
        if (promptInfo == null || promptInfo.getErrorMsg() == null) {
            return false;
        }
        TestMessage errorMsg = promptInfo.getErrorMsg();
        return errorMsg.getErrorType() != null
                || (errorMsg.getErrorMessage() != null && !errorMsg.getErrorMessage().isEmpty());
    }

    private Map<String, String> constructorDeps(PromptInfo promptInfo) {
        if (promptInfo == null || promptInfo.getConstructorDeps() == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(promptInfo.getConstructorDeps());
    }

    private Map<String, String> methodDeps(PromptInfo promptInfo) {
        if (promptInfo == null || promptInfo.getMethodDeps() == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(promptInfo.getMethodDeps());
    }

    private String joinLines(List<String> values) {
        return values == null ? "" : String.join("\n", values);
    }

    private String scenarioGuidance(ScoutMethodState methodState) {
        return methodState == null ? "" : safe(methodState.getScenarioGuidance());
    }

    private boolean isScenarioGuidedCoverage(ScoutPromptContext context, String scenarioGuidance) {
        return context != null
                && context.getMode() == ScoutPromptMode.GUIDE_COVERAGE
                && !safe(scenarioGuidance).isEmpty();
    }

    private String uncoveredScenarioDescriptions(ScoutMethodState methodState) {
        if (methodState == null || methodState.getUncoveredScenarioIds() == null || methodState.getScenarios() == null) {
            return "";
        }
        ArrayList<String> descriptions = new ArrayList<>();
        for (String scenarioId : methodState.getUncoveredScenarioIds()) {
            ScoutScenario scenario = findScenario(methodState.getScenarios(), scenarioId);
            if (scenario != null) {
                descriptions.add(safe(scenario.getDescription()));
            }
        }
        return joinLines(descriptions);
    }

    private String uncoveredScenarioStatusDescriptions(ScoutMethodState methodState) {
        if (methodState == null || methodState.getUncoveredScenarioIds() == null || methodState.getScenarios() == null) {
            return "";
        }
        ArrayList<String> descriptions = new ArrayList<>();
        for (String scenarioId : methodState.getUncoveredScenarioIds()) {
            ScoutScenario scenario = findScenario(methodState.getScenarios(), scenarioId);
            if (scenario != null) {
                descriptions.add(scenarioStatus(scenario, "Relevant repair evidence"));
            }
        }
        return joinLines(descriptions);
    }

    private String coveredScenarioDescriptions(ScoutMethodState methodState) {
        if (methodState == null || methodState.getScenarios() == null) {
            return "";
        }
        ArrayList<String> descriptions = new ArrayList<>();
        for (ScoutScenario scenario : methodState.getScenarios()) {
            if (scenario != null && scenario.isCovered()) {
                descriptions.add(scenarioStatus(scenario, "Relevant repair evidence"));
            }
        }
        return joinLines(descriptions);
    }

    private String taggedScenarioAttempts(ScoutMethodState methodState) {
        if (methodState == null || methodState.getTaggedScenarioAttemptsByAttempt() == null) {
            return "";
        }
        ArrayList<String> descriptions = new ArrayList<>();
        Set<String> uncoveredScenarioIds = new HashSet<>();
        if (methodState.getUncoveredScenarioIds() != null) {
            uncoveredScenarioIds.addAll(methodState.getUncoveredScenarioIds());
        }

        for (Map.Entry<String, List<ScoutScenarioTag>> entry : methodState.getTaggedScenarioAttemptsByAttempt().entrySet()) {
            List<ScoutScenarioTag> tags = entry.getValue();
            if (tags == null || tags.isEmpty()) {
                continue;
            }
            Set<String> coveredScenarioIds = coveredScenarioIds(methodState, entry.getKey());
            for (ScoutScenarioTag tag : tags) {
                String scenarioId = safe(tag == null ? "" : tag.getScenarioId()).trim();
                if (scenarioId.isEmpty()) {
                    continue;
                }
                descriptions.add("- " + scenarioId + " via " + testMethodName(tag) + ": "
                        + scenarioAttemptStatus(scenarioId, coveredScenarioIds, uncoveredScenarioIds));
            }
        }
        return joinLines(descriptions);
    }

    private String taggedScenarioTestExamples(ScoutClassState classState, ScoutMethodState methodState) {
        if (classState == null || classState.getCachedTestsByAttempt() == null || methodState == null
                || methodState.getTaggedScenarioAttemptsByAttempt() == null) {
            return "";
        }
        Set<String> targetScenarioIds = targetScenarioIds(methodState);
        if (targetScenarioIds.isEmpty()) {
            return "";
        }

        LinkedHashSet<String> examples = new LinkedHashSet<>();
        for (Map.Entry<String, List<ScoutScenarioTag>> entry : methodState.getTaggedScenarioAttemptsByAttempt().entrySet()) {
            String code = safe(classState.getCachedTestsByAttempt().get(entry.getKey()));
            if (code.trim().isEmpty()) {
                continue;
            }
            List<ScoutScenarioTag> tags = entry.getValue();
            if (tags == null) {
                continue;
            }
            for (ScoutScenarioTag tag : tags) {
                String scenarioId = safe(tag == null ? "" : tag.getScenarioId()).trim();
                if (!targetScenarioIds.contains(scenarioId)) {
                    continue;
                }
                String snippet = taggedSnippet(code, scenarioId, testMethodName(tag));
                if (!snippet.isEmpty()) {
                    examples.add("[" + scenarioId + " via " + testMethodName(tag) + "]\n" + snippet);
                }
            }
        }
        return joinLines(new ArrayList<>(examples));
    }

    private Set<String> targetScenarioIds(ScoutMethodState methodState) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (methodState != null && methodState.getUncoveredScenarioIds() != null) {
            ids.addAll(methodState.getUncoveredScenarioIds());
        }
        return ids;
    }

    private String taggedSnippet(String code, String scenarioId, String methodName) {
        String[] lines = code.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            String tagScenarioId = scenarioId(lines[index]);
            if (!scenarioId.equals(tagScenarioId)) {
                continue;
            }
            ArrayList<String> snippetLines = collectTaggedMethod(lines, index);
            if (snippetLines.isEmpty()) {
                continue;
            }
            if (methodName.equals("tagged test") || snippetContainsMethod(snippetLines, methodName)) {
                return normalizeIndent(snippetLines);
            }
        }
        return "";
    }

    private ArrayList<String> collectTaggedMethod(String[] lines, int tagIndex) {
        ArrayList<String> snippetLines = new ArrayList<>();
        snippetLines.add(lines[tagIndex]);

        boolean sawMethod = false;
        int braceDepth = 0;
        for (int index = tagIndex + 1; index < lines.length; index++) {
            String line = lines[index];
            if (!sawMethod && !scenarioId(line).isEmpty()) {
                break;
            }
            snippetLines.add(line);
            if (!sawMethod && !methodName(line).isEmpty()) {
                sawMethod = true;
            }
            if (sawMethod) {
                braceDepth += count(line, '{') - count(line, '}');
                if (braceDepth <= 0 && (line.contains("{") || line.contains("}"))) {
                    break;
                }
            }
        }
        return sawMethod ? snippetLines : new ArrayList<String>();
    }

    private String scenarioId(String line) {
        Matcher matcher = SCENARIO_TAG_PATTERN.matcher(safe(line));
        return matcher.matches() ? matcher.group(1).trim() : "";
    }

    private boolean snippetContainsMethod(List<String> lines, String methodName) {
        for (String line : lines) {
            if (methodName.equals(methodName(line))) {
                return true;
            }
        }
        return false;
    }

    private String methodName(String line) {
        String trimmed = safe(line).trim();
        if (!trimmed.contains("(") || trimmed.startsWith("if ") || trimmed.startsWith("for ")
                || trimmed.startsWith("while ") || trimmed.startsWith("switch ")) {
            return "";
        }
        Matcher matcher = METHOD_PATTERN.matcher(trimmed);
        return matcher.matches() ? matcher.group(1) : "";
    }

    private String normalizeIndent(List<String> lines) {
        int indent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (safe(line).trim().isEmpty()) {
                continue;
            }
            indent = Math.min(indent, leadingSpaces(line));
        }
        if (indent == Integer.MAX_VALUE) {
            return "";
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (String line : lines) {
            normalized.add(line.length() >= indent ? line.substring(indent) : line.trim());
        }
        return joinLines(normalized).trim();
    }

    private int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
            count++;
        }
        return count;
    }

    private int count(String line, char target) {
        int total = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) == target) {
                total++;
            }
        }
        return total;
    }

    private Set<String> coveredScenarioIds(ScoutMethodState methodState, String attemptKey) {
        Set<String> coveredScenarioIds = new HashSet<>();
        if (methodState == null || methodState.getCoveredScenarioIdsByAttempt() == null) {
            return coveredScenarioIds;
        }
        List<String> ids = methodState.getCoveredScenarioIdsByAttempt().get(attemptKey);
        if (ids != null) {
            coveredScenarioIds.addAll(ids);
        }
        return coveredScenarioIds;
    }

    private String testMethodName(ScoutScenarioTag tag) {
        String methodName = safe(tag == null ? "" : tag.getMethodName()).trim();
        return methodName.isEmpty() ? "tagged test" : methodName;
    }

    private String scenarioAttemptStatus(String scenarioId, Set<String> coveredScenarioIds, Set<String> uncoveredScenarioIds) {
        if (coveredScenarioIds != null && coveredScenarioIds.contains(scenarioId)) {
            return "covered";
        }
        if (uncoveredScenarioIds != null && uncoveredScenarioIds.contains(scenarioId)) {
            return "attempted, but still uncovered";
        }
        return "attempted";
    }

    private String scenarioStatus(ScoutScenario scenario, String branchLabel) {
        if (scenario == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(safe(scenario.getDescription()));
        String branchStatements = branchStatements(scenario);
        if (!branchStatements.isEmpty()) {
            builder.append("\n  ")
                    .append(branchLabel)
                    .append(": ")
                    .append(branchStatements);
        }
        return builder.toString();
    }

    private String branchStatements(ScoutScenario scenario) {
        if (scenario == null || scenario.getBranchMappings() == null || scenario.getBranchMappings().isEmpty()) {
            return "";
        }
        ArrayList<String> statements = new ArrayList<>();
        for (ScoutBranchMapping mapping : scenario.getBranchMappings()) {
            String statement = branchStatement(mapping);
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
        }
        return String.join("; ", statements);
    }

    private String branchStatement(ScoutBranchMapping mapping) {
        if (mapping == null || ScoutBranchMapping.UNKNOWN.equals(mapping.getOutcome())) {
            return "";
        }
        String branchText = safe(mapping.getBranchText()).trim();
        if (branchText.isEmpty()) {
            branchText = "condition";
        }
        return mapping.getOutcome() + " path of " + branchText;
    }

    private ScoutScenario findScenario(List<ScoutScenario> scenarios, String scenarioId) {
        if (scenarioId == null) {
            return null;
        }
        for (ScoutScenario scenario : scenarios) {
            if (scenario != null && scenarioId.equals(scenario.getId())) {
                return scenario;
            }
        }
        return null;
    }

    private boolean hasConstructorSetup(ScoutClassState classState) {
        if (classState == null) {
            return false;
        }
        return classState.isPrivateConstructor() || !safe(classState.getConstructorCalls()).isEmpty();
    }

    private int round(PromptInfo promptInfo) {
        if (promptInfo == null || promptInfo.getRound() == null) {
            return 0;
        }
        return promptInfo.getRound();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
