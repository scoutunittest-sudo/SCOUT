package zju.cst.aces.scout.description;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ChatMessage;
import zju.cst.aces.scout.agent.ScoutAgent;
import zju.cst.aces.scout.agent.ScoutAgents;
import zju.cst.aces.scout.agent.ScoutAgentRequest;
import zju.cst.aces.scout.agent.ScoutAgentResult;
import zju.cst.aces.scout.agent.ScoutAgentTask;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.scout.prompt.ScoutPromptContext;
import zju.cst.aces.scout.prompt.ScoutPromptRenderer;
import zju.cst.aces.scout.scenario.ScoutBranchMapping;
import zju.cst.aces.scout.scenario.ScoutScenario;
import zju.cst.aces.scout.scenario.ScoutScenarioAnalyzer;
import zju.cst.aces.scout.state.ScoutClassState;
import zju.cst.aces.scout.state.ScoutMethodState;
import zju.cst.aces.scout.state.ScoutProjectState;
import zju.cst.aces.scout.state.ScoutStateStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScoutDescriptionService {
    private static final String PROJECT_SYSTEM_TEMPLATE = "scout_export_project_system.ftl";
    private static final String PROJECT_TEMPLATE = "scout_export_project.ftl";
    private static final String CLASS_SYSTEM_TEMPLATE = "scout_export_class_system.ftl";
    private static final String CLASS_TEMPLATE = "scout_export_class.ftl";
    private static final String CLASS_ASPECT_TEMPLATE = "scout_export_class_aspect.ftl";
    private static final String METHOD_SYSTEM_TEMPLATE = "scout_export_method_system.ftl";
    private static final String METHOD_TEMPLATE = "scout_export_method.ftl";
    private static final String METHOD_SCENARIO_TEMPLATE = "scout_export_method_scenario.ftl";

    private final Config config;
    private final ScoutStateStore store;
    private final ScoutPromptRenderer renderer;
    private final ScoutAgent agent;
    private final ScoutScenarioAnalyzer scenarioAnalyzer;

    public ScoutDescriptionService(Config config, ScoutStateStore store) {
        this(config, store, ScoutAgents.resolve(config));
    }

    ScoutDescriptionService(Config config, ScoutStateStore store, ScoutAgent agent) {
        this.config = config;
        this.store = store;
        this.renderer = new ScoutPromptRenderer(config == null ? null : config.getPromptPath());
        this.agent = agent == null ? ScoutAgents.resolve(config) : agent;
        this.scenarioAnalyzer = new ScoutScenarioAnalyzer();
    }

    public void ensureDescriptions(ClassInfo classInfo,
                                   MethodInfo methodInfo,
                                   ScoutProjectState projectState,
                                   ScoutClassState classState,
                                   ScoutMethodState methodState) {
        ensureProjectDescription(projectState);
        ensureClassDescription(classInfo, projectState, classState);
        ensureMethodDescription(methodInfo, projectState, classState, methodState);
    }

    public void ensureProjectDescription(ScoutProjectState state) {
        if (state == null || isNonEmpty(state.getProjectSummary())) {
            return;
        }

        String readme = readReadme();
        state.setReadme(readme);

        ScoutPromptContext context = context(dataModel(
                "readme", readme,
                "project_summary", "",
                "class_name", "",
                "class_code", "",
                "class_summary", "",
                "checklist", "",
                "method_sig", "",
                "method_code", "",
                "method_summary", ""
        ));
        String content = callDescriptionPrompt(ScoutAgentTask.PROJECT_SUMMARY,
                PROJECT_SYSTEM_TEMPLATE, PROJECT_TEMPLATE, context);
        state.setProjectSummary(extractDelimited(content, "SUMMARY"));
        saveProjectState(state);
    }

    public void ensureClassDescription(ClassInfo classInfo,
                                       ScoutProjectState projectState,
                                       ScoutClassState classState) {
        if (classInfo == null || classState == null) {
            return;
        }

        fillClassIdentity(classInfo, classState);

        if (!isNonEmpty(classState.getClassSummary())) {
            ScoutPromptContext summaryContext = context(dataModel(
                    "readme", safe(projectState == null ? "" : projectState.getReadme()),
                    "project_summary", safe(projectState == null ? "" : projectState.getProjectSummary()),
                    "class_name", className(classInfo),
                    "class_code", classCode(classInfo),
                    "class_summary", "",
                    "checklist", safe(classState.getChecklist()),
                    "method_sig", "",
                    "method_code", "",
                    "method_summary", ""
            ));
            String content = callDescriptionPrompt(ScoutAgentTask.CLASS_SUMMARY,
                    CLASS_SYSTEM_TEMPLATE, CLASS_TEMPLATE, summaryContext);
            classState.setClassSummary(extractDelimited(content, "SUMMARY"));
        }

        if (!isNonEmpty(classState.getChecklist())) {
            ScoutPromptContext aspectContext = context(dataModel(
                    "readme", safe(projectState == null ? "" : projectState.getReadme()),
                    "project_summary", safe(projectState == null ? "" : projectState.getProjectSummary()),
                    "class_name", className(classInfo),
                    "class_code", classCode(classInfo),
                    "class_summary", safe(classState.getClassSummary()),
                    "checklist", "",
                    "method_sig", "",
                    "method_code", "",
                    "method_summary", ""
            ));
            String content = callDescriptionPrompt(ScoutAgentTask.CLASS_CHECKLIST,
                    CLASS_SYSTEM_TEMPLATE, CLASS_ASPECT_TEMPLATE, aspectContext);
            classState.setChecklist(extractDelimited(content, "COMPONENTS"));
        }

        saveClassState(classState);
    }

    public void ensureMethodDescription(MethodInfo methodInfo,
                                        ScoutProjectState projectState,
                                        ScoutClassState classState,
                                        ScoutMethodState methodState) {
        if (methodState == null) {
            return;
        }

        if (methodInfo != null) {
            fillMethodIdentity(methodInfo, classState, methodState);
        }

        if (methodInfo != null && !isNonEmpty(methodState.getMethodSummary())) {
            ScoutPromptContext summaryContext = context(dataModel(
                    "readme", safe(projectState == null ? "" : projectState.getReadme()),
                    "project_summary", safe(projectState == null ? "" : projectState.getProjectSummary()),
                    "class_name", safe(classState == null ? methodInfo.getClassName() : classState.getClassName()),
                    "class_code", "",
                    "class_summary", safe(classState == null ? "" : classState.getClassSummary()),
                    "checklist", safe(classState == null ? "" : classState.getChecklist()),
                    "method_sig", safe(methodInfo.getMethodSignature()),
                    "method_code", safe(methodInfo.getSourceCode()),
                    "method_summary", ""
            ));
            String content = callDescriptionPrompt(ScoutAgentTask.METHOD_SUMMARY,
                    METHOD_SYSTEM_TEMPLATE, METHOD_TEMPLATE, summaryContext);
            methodState.setMethodSummary(extractDelimited(content, "SUMMARY"));
        }

        if (methodInfo != null && !isNonEmpty(methodState.getScenario())) {
            ScoutPromptContext scenarioContext = context(dataModel(
                    "readme", safe(projectState == null ? "" : projectState.getReadme()),
                    "project_summary", safe(projectState == null ? "" : projectState.getProjectSummary()),
                    "class_name", safe(classState == null ? methodInfo.getClassName() : classState.getClassName()),
                    "class_code", "",
                    "class_summary", safe(classState == null ? "" : classState.getClassSummary()),
                    "checklist", safe(classState == null ? "" : classState.getChecklist()),
                    "method_sig", safe(methodInfo.getMethodSignature()),
                    "method_code", safe(methodInfo.getSourceCode()),
                    "method_summary", safe(methodState.getMethodSummary())
            ));
            String content = callDescriptionPrompt(ScoutAgentTask.METHOD_SCENARIOS,
                    METHOD_SYSTEM_TEMPLATE, METHOD_SCENARIO_TEMPLATE, scenarioContext);
            methodState.setScenario(extractDelimited(content, "SCENARIO"));
        }

        analyzeMethodScenarios(methodInfo, methodState);
        saveMethodState(methodState);
    }

    private void analyzeMethodScenarios(MethodInfo methodInfo, ScoutMethodState methodState) {
        if (!isNonEmpty(methodState.getScenario()) || !needsScenarioAnalysis(methodState)) {
            return;
        }
        try {
            List<ScoutScenario> analyzedScenarios = scenarioAnalyzer.analyze(
                    methodState.getScenario(),
                    methodInfo == null ? "" : methodInfo.getSourceCode(),
                    methodInfo == null ? 1 : methodInfo.getSourceStartLine());
            mergeAnalyzedScenarios(methodState, analyzedScenarios);
        } catch (RuntimeException e) {
            warn("SCOUT scenario analysis failed: " + e.getMessage());
        }
    }

    private boolean needsScenarioAnalysis(ScoutMethodState methodState) {
        if (methodState.getScenarios() == null || methodState.getScenarios().isEmpty()) {
            return true;
        }
        for (ScoutScenario scenario : methodState.getScenarios()) {
            if (scenario != null && isUnmappedScenario(scenario)) {
                return true;
            }
        }
        return false;
    }

    private void mergeAnalyzedScenarios(ScoutMethodState methodState, List<ScoutScenario> analyzedScenarios) {
        if (methodState.getScenarios() == null || methodState.getScenarios().isEmpty()) {
            methodState.setScenarios(new ArrayList<ScoutScenario>(analyzedScenarios));
            return;
        }
        for (ScoutScenario existing : methodState.getScenarios()) {
            if (existing == null || !isUnmappedScenario(existing)) {
                continue;
            }
            ScoutScenario analyzed = findMatchingScenario(analyzedScenarios, existing);
            if (analyzed != null) {
                copyScenarioAnalysis(existing, analyzed);
            }
        }
    }

    private ScoutScenario findMatchingScenario(List<ScoutScenario> scenarios, ScoutScenario target) {
        if (scenarios == null || target == null) {
            return null;
        }
        String targetId = safe(target.getId());
        String targetDescription = safe(target.getDescription());
        for (ScoutScenario scenario : scenarios) {
            if (scenario == null) {
                continue;
            }
            if ((!targetId.isEmpty() && targetId.equals(safe(scenario.getId())))
                    || (!targetDescription.isEmpty() && targetDescription.equals(safe(scenario.getDescription())))) {
                return scenario;
            }
        }
        return null;
    }

    private boolean isUnmappedScenario(ScoutScenario scenario) {
        return (scenario.getBranchHints() == null || scenario.getBranchHints().isEmpty())
                && (scenario.getArgumentHints() == null || scenario.getArgumentHints().isEmpty())
                && (scenario.getDependencyHints() == null || scenario.getDependencyHints().isEmpty())
                && (scenario.getRelatedLines() == null || scenario.getRelatedLines().isEmpty());
    }

    private void copyScenarioAnalysis(ScoutScenario target, ScoutScenario source) {
        target.setBranchHints(source.getBranchHints() == null
                ? new ArrayList<String>()
                : new ArrayList<String>(source.getBranchHints()));
        target.setArgumentHints(source.getArgumentHints() == null
                ? new ArrayList<String>()
                : new ArrayList<String>(source.getArgumentHints()));
        target.setDependencyHints(source.getDependencyHints() == null
                ? new ArrayList<String>()
                : new ArrayList<String>(source.getDependencyHints()));
        target.setRelatedLines(source.getRelatedLines() == null
                ? new ArrayList<Integer>()
                : new ArrayList<Integer>(source.getRelatedLines()));
        target.setBranchMappings(source.getBranchMappings() == null
                ? new ArrayList<ScoutBranchMapping>()
                : new ArrayList<ScoutBranchMapping>(source.getBranchMappings()));
    }

    public static String extractDelimited(String content, String delimiter) {
        if (content == null) {
            return "";
        }
        if (delimiter == null || delimiter.length() == 0) {
            return content.trim();
        }
        String open = "[" + delimiter + "]";
        String close = "[/" + delimiter + "]";
        int start = content.indexOf(open);
        if (start < 0) {
            return content.trim();
        }
        int blockStart = start + open.length();
        int end = content.indexOf(close, blockStart);
        if (end < 0) {
            return content.trim();
        }
        return content.substring(blockStart, end).trim();
    }

    private String callDescriptionPrompt(ScoutAgentTask task, String systemTemplate, String userTemplate,
                                         ScoutPromptContext context) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.ofSystem(renderer.renderSystem(systemTemplate, context)));
            messages.add(ChatMessage.of(renderer.renderSystem(userTemplate, context)));
            ScoutAgentResult result = agent.run(new ScoutAgentRequest(config, task, messages));
            return safe(result == null ? "" : result.getContent());
        } catch (Exception e) {
            warn("SCOUT description prompt failed: " + e.getMessage());
            return "";
        }
    }

    private ScoutPromptContext context(Map<String, Object> dataModel) {
        ScoutPromptContext context = new ScoutPromptContext();
        context.setDataModel(dataModel);
        return context;
    }

    private Map<String, Object> dataModel(Object... pairs) {
        Map<String, Object> dataModel = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            dataModel.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return dataModel;
    }

    private String readReadme() {
        if (config == null) {
            warn("SCOUT description README lookup skipped: config is null");
            return "";
        }
        if (config.getProject() == null) {
            warn("SCOUT description README lookup skipped: project is null");
            return "";
        }
        if (config.getProject().getBasedir() == null) {
            warn("SCOUT description README lookup skipped: project basedir is null");
            return "";
        }
        Path current = config.getProject().getBasedir().toPath().toAbsolutePath();
        while (current != null) {
            Path readme = findReadme(current);
            if (readme != null) {
                try {
                    return new String(Files.readAllBytes(readme), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    warn("Failed to read README for SCOUT description: " + readme + " (" + e.getMessage() + ")");
                    return "";
                }
            }
            current = current.getParent();
        }
        return "";
    }

    private Path findReadme(Path directory) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().startsWith("readme")) {
                    return path;
                }
            }
        } catch (IOException e) {
            warn("Failed to scan for README in " + directory + ": " + e.getMessage());
        }
        return null;
    }

    private void fillClassIdentity(ClassInfo classInfo, ScoutClassState classState) {
        if (!isNonEmpty(classState.getFullClassName())) {
            classState.setFullClassName(safe(classInfo.getFullClassName()));
        }
        if (!isNonEmpty(classState.getClassName())) {
            classState.setClassName(safe(classInfo.getClassName()));
        }
        String constructorContext = constructorContext(classInfo);
        if (!isNonEmpty(classState.getConstructorCalls()) && isNonEmpty(constructorContext)) {
            classState.setConstructorCalls(constructorContext);
        }
        if (!classState.isPrivateConstructor() && hasPrivateConstructorSignal(classInfo)) {
            classState.setPrivateConstructor(true);
        }
    }

    private String constructorContext(ClassInfo classInfo) {
        if (classInfo == null || !classInfo.hasConstructor) {
            return "";
        }
        String constructorBrief = joinNonEmpty(classInfo.constructorBrief);
        if (isNonEmpty(constructorBrief)) {
            return constructorBrief;
        }
        return joinNonEmpty(classInfo.constructorSigs);
    }

    private String joinNonEmpty(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> nonEmptyValues = new ArrayList<>();
        for (String value : values) {
            if (isNonEmpty(value)) {
                nonEmptyValues.add(value.trim());
            }
        }
        return String.join("\n", nonEmptyValues);
    }

    private boolean hasPrivateConstructorSignal(String constructorContext) {
        if (!isNonEmpty(constructorContext)) {
            return false;
        }
        String normalized = constructorContext.replaceAll("\\s+", " ").trim();
        return normalized.startsWith("private ") || normalized.contains(" private ");
    }

    private boolean hasPrivateConstructorSignal(ClassInfo classInfo) {
        return classInfo != null
                && classInfo.hasConstructor
                && (hasPrivateConstructorSignal(joinNonEmpty(classInfo.constructorBrief))
                || hasPrivateConstructorSignal(joinNonEmpty(classInfo.constructorSigs)));
    }

    private void fillMethodIdentity(MethodInfo methodInfo, ScoutClassState classState, ScoutMethodState methodState) {
        if (!isNonEmpty(methodState.getFullClassName())) {
            String fullClassName = classState == null ? "" : classState.getFullClassName();
            methodState.setFullClassName(isNonEmpty(fullClassName) ? fullClassName : safe(methodInfo.getClassName()));
        }
        if (!isNonEmpty(methodState.getMethodSignature())) {
            methodState.setMethodSignature(safe(methodInfo.getMethodSignature()));
        }
    }

    private String className(ClassInfo classInfo) {
        if (isNonEmpty(classInfo.getFullClassName())) {
            return classInfo.getFullClassName();
        }
        return safe(classInfo.getClassName());
    }

    private String classCode(ClassInfo classInfo) {
        if (isNonEmpty(classInfo.getClassDeclarationCode())) {
            return classInfo.getClassDeclarationCode();
        }
        return safe(classInfo.getCompilationUnitCode());
    }

    private void saveProjectState(ScoutProjectState state) {
        try {
            store.saveProjectState(state);
        } catch (IOException e) {
            warn("Failed to save SCOUT project state: " + e.getMessage());
        }
    }

    private void saveClassState(ScoutClassState state) {
        try {
            store.saveClassState(state);
        } catch (IOException e) {
            warn("Failed to save SCOUT class state: " + e.getMessage());
        }
    }

    private void saveMethodState(ScoutMethodState state) {
        try {
            store.saveMethodState(state);
        } catch (IOException e) {
            warn("Failed to save SCOUT method state: " + e.getMessage());
        }
    }

    private void warn(String message) {
        if (config != null && config.getLogger() != null) {
            config.getLogger().warn(message);
        }
    }

    private static boolean isNonEmpty(String value) {
        return value != null && value.trim().length() > 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
