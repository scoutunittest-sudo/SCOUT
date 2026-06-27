package zju.cst.aces.scout.generation;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.ChatGenerator;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.RepairImpl;
import zju.cst.aces.dto.ChatMessage;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.dto.RoundRecord;
import zju.cst.aces.dto.TestSkeleton;
import zju.cst.aces.runner.MethodRunner;
import zju.cst.aces.scout.agent.ScoutAgent;
import zju.cst.aces.scout.agent.ScoutAgentRequest;
import zju.cst.aces.scout.agent.ScoutAgentResult;
import zju.cst.aces.scout.agent.ScoutAgentTask;
import zju.cst.aces.scout.agent.ScoutAgents;
import zju.cst.aces.scout.analysis.ScoutCoverageAnalyzer;
import zju.cst.aces.scout.analysis.ScoutCoverageResult;
import zju.cst.aces.scout.analysis.ScoutCoverageState;
import zju.cst.aces.scout.analysis.ScoutUncoveredRegion;
import zju.cst.aces.scout.description.ScoutDescriptionService;
import zju.cst.aces.scout.prompt.ScoutPromptContext;
import zju.cst.aces.scout.prompt.ScoutPromptContextBuilder;
import zju.cst.aces.scout.prompt.ScoutPromptMode;
import zju.cst.aces.scout.prompt.ScoutPromptRenderer;
import zju.cst.aces.scout.scenario.ScoutScenarioTag;
import zju.cst.aces.scout.scenario.ScoutScenarioTagExtractor;
import zju.cst.aces.scout.scenario.ScoutScenarioCoverageState;
import zju.cst.aces.scout.state.ScoutClassState;
import zju.cst.aces.scout.state.ScoutMethodState;
import zju.cst.aces.scout.state.ScoutProjectState;
import zju.cst.aces.scout.state.ScoutStateStore;
import zju.cst.aces.util.CodeExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static zju.cst.aces.runner.AbstractRunner.exportValidatedTest;

public class ScoutTestGeneration {
    private static final String SYSTEM_PROMPT = "You are SCOUT, a Java unit test generator. "
            + "Return only compilable Java test code for the requested method.";

    private final Config config;
    private final ScoutStateStore store;
    private final DescriptionService descriptionService;
    private final ScoutPromptContextBuilder contextBuilder;
    private final PromptRenderer promptRenderer;
    private final ScoutAgent agent;
    private final CoverageAnalyzer coverageAnalyzer;
    private final TokenChecker tokenChecker;

    public ScoutTestGeneration(Config config, ScoutStateStore store) {
        this(config,
                store,
                descriptionService(config, store),
                new ScoutPromptContextBuilder(),
                new RealPromptRenderer(new ScoutPromptRenderer(config == null ? null : config.getPromptPath())),
                ScoutAgents.resolve(config),
                new RealCoverageAnalyzer(new ScoutCoverageAnalyzer(config)),
                new RealTokenChecker(config));
    }

    ScoutTestGeneration(Config config,
                        ScoutStateStore store,
                        DescriptionService descriptionService,
                        ScoutPromptContextBuilder contextBuilder,
                        PromptRenderer promptRenderer,
                        ScoutAgent agent,
                        CoverageAnalyzer coverageAnalyzer,
                        TokenChecker tokenChecker) {
        this.config = config;
        this.store = store;
        this.descriptionService = descriptionService;
        this.contextBuilder = contextBuilder;
        this.promptRenderer = promptRenderer;
        this.agent = agent == null ? ScoutAgents.resolve(config) : agent;
        this.coverageAnalyzer = coverageAnalyzer;
        this.tokenChecker = tokenChecker;
    }

    public void execute(PromptConstructorImpl pc) {
        if (pc == null || pc.getPromptInfo() == null) {
            return;
        }

        PromptInfo promptInfo = pc.getPromptInfo();
        int round = safeInteger(promptInfo.getRound(), 0);
        RoundRecord record = ensureRecord(promptInfo, round);
        record.setAttempt(safeInteger(promptInfo.getTestNum(), 0));

        ScoutProjectState projectState = loadProjectState();
        ScoutClassState classState = loadClassState(promptInfo.getFullClassName());
        ScoutMethodState methodState = loadMethodState(promptInfo.getFullClassName(), promptInfo.getMethodSignature());

        ensureDescriptions(promptInfo, projectState, classState, methodState);

        ScoutPromptContext context = contextBuilder.build(promptInfo, projectState, classState, methodState);
        List<ChatMessage> prompt = new ArrayList<ChatMessage>();
        prompt.add(ChatMessage.ofSystem(promptRenderer.renderSystem(context)));
        prompt.add(ChatMessage.of(promptRenderer.renderUser(context)));
        record.setPrompt(prompt);


        debug("[SCOUT Prompt]:\n" + prompt);
        ScoutAgentResult chatResult = agent.run(new ScoutAgentRequest(config, taskFor(context), prompt));
        String content = chatResult == null ? "" : safe(chatResult.getContent());
        debug("[SCOUT Response]:\n" + content);

        String extractedCode = ChatGenerator.extractCodeByContent(content);
        record.setPromptToken(chatResult == null ? 0 : chatResult.getPromptTokens());
        record.setResponseToken(chatResult == null ? 0 : chatResult.getResponseTokens());
        record.setResponse(content);
        if (extractedCode.length() == 0) {
            info("SCOUT test generation extracted no code for method < " + promptInfo.getMethodName() + " >");
            recordNoCode(record, record.getPromptToken(), record.getResponseToken(), content, "");
            promptInfo.setUnitTest("");
            saveStates(classState, methodState);
            return;
        }

        String code = finalizeGeneratedCode(extractedCode, promptInfo, pc);
        promptInfo.setUnitTest(code);
        record.setCode(code);
        record.setHasCode(true);
        saveStates(classState, methodState);
    }

    public boolean recordValidatedTest(PromptConstructorImpl pc) {
        if (pc == null || pc.getPromptInfo() == null) {
            return true;
        }
        PromptInfo promptInfo = pc.getPromptInfo();
        String code = safe(promptInfo.getUnitTest());
        if (code.length() == 0) {
            return true;
        }
        ScoutCoverageResult coverageResult = coverageAnalyzer.analyze(code, pc.getFullTestName(), promptInfo);
        return recordValidatedTest(pc, coverageResult);
    }

    private boolean recordValidatedTest(PromptConstructorImpl pc, ScoutCoverageResult coverageResult) {
        if (pc == null || pc.getPromptInfo() == null) {
            return true;
        }

        PromptInfo promptInfo = pc.getPromptInfo();
        String code = safe(promptInfo.getUnitTest());
        if (code.length() == 0) {
            return true;
        }
        int round = safeInteger(promptInfo.getRound(), 0);
        RoundRecord record = ensureRecord(promptInfo, round);
        record.setAttempt(safeInteger(promptInfo.getTestNum(), 0));

        ScoutClassState classState = loadClassState(promptInfo.getFullClassName());
        ScoutMethodState methodState = loadMethodState(promptInfo.getFullClassName(), promptInfo.getMethodSignature());
        String attemptKey = attemptKey(promptInfo, record);
        boolean firstValidatedTest = methodState.getUncoveredRegionsByAttempt() == null
                || methodState.getUncoveredRegionsByAttempt().isEmpty();

        if (coverageResult == null || coverageResult.score() < 0.0) {
            if (firstValidatedTest) {
                boolean exported = exportAcceptedTest(code, pc.getFullTestName(), promptInfo, round, record.getAttempt(), true);
                if (!exported) {
                    saveStates(classState, methodState);
                    return false;
                }
                cacheAcceptedTest(classState, methodState, attemptKey, code);
            }
            saveStates(classState, methodState);
            return true;
        }

        recordCoverage(methodState, attemptKey, coverageResult, code);
        new ScoutScenarioCoverageState(methodState).recordAttempt(attemptKey, coverageResult);
        recordTaggedScenarioAttempts(methodState, attemptKey, code);

        boolean exported = exportAcceptedTest(code, pc.getFullTestName(), promptInfo, round, record.getAttempt(), coverageResult.isComplete());
        if (!exported) {
            saveStates(classState, methodState);
            return false;
        }
        cacheAcceptedTest(classState, methodState, attemptKey, code);
        saveStates(classState, methodState);
        return coverageResult.isComplete();
    }

    private boolean archiveIncompleteValidCandidate(String code, String fullTestName, PromptInfo promptInfo, int round, int attempt) {
        if (config == null || config.getTestOutput() == null || safe(code).trim().isEmpty()
                || safe(fullTestName).trim().isEmpty()) {
            return false;
        }

        String originalClassName = simpleName(fullTestName);
        String archivedClassName = archivedClassName(originalClassName, round, attempt);
        Path archivePath = archivePath(fullTestName, archivedClassName);
        String archivedFullTestName = archivedFullTestName(fullTestName, archivedClassName);
        try {
            Files.createDirectories(archivePath.getParent());
            return exportValidatedTest(config,
                    renameTestClass(code, originalClassName, archivedClassName),
                    archivePath,
                    archivedFullTestName,
                    promptInfo);
        } catch (IOException e) {
            warn("SCOUT failed to archive incomplete valid test candidate: " + e.getMessage());
            return false;
        }
    }

    private String archivedClassName(String originalClassName, int round, int attempt) {
        String suffix = "_round" + round + "_attempt" + attempt;
        if (safe(originalClassName).endsWith("_Test")) {
            return originalClassName.substring(0, originalClassName.length() - "_Test".length()) + suffix + "_Test";
        }
        if (safe(originalClassName).endsWith("Test")) {
            return originalClassName.substring(0, originalClassName.length() - "Test".length()) + suffix + "Test";
        }
        return originalClassName + suffix + "_Test";
    }

    private Path archivePath(String fullTestName, String archivedClassName) {
        String packageName = packageName(fullTestName);
        Path output = config.getTestOutput();
        if (!packageName.isEmpty()) {
            output = output.resolve(packageName.replace(".", java.io.File.separator));
        }
        return output.resolve(archivedClassName + ".java");
    }

    private String archivedFullTestName(String fullTestName, String archivedClassName) {
        String packageName = packageName(fullTestName);
        return packageName.isEmpty() ? archivedClassName : packageName + "." + archivedClassName;
    }

    private String renameTestClass(String code, String originalClassName, String archivedClassName) {
        return safe(code).replaceAll("\\b" + Pattern.quote(originalClassName) + "\\b", archivedClassName);
    }

    private String packageName(String fullTestName) {
        String safeName = safe(fullTestName);
        int separator = safeName.lastIndexOf('.');
        return separator < 0 ? "" : safeName.substring(0, separator);
    }

    private String simpleName(String fullTestName) {
        String safeName = safe(fullTestName);
        int separator = safeName.lastIndexOf('.');
        return separator < 0 ? safeName : safeName.substring(separator + 1);
    }

    private String finalizeGeneratedCode(String code, PromptInfo promptInfo, PromptConstructorImpl pc) {
        if (CodeExtractor.isTestMethod(code)) {
            return new TestSkeleton(promptInfo).build(code);
        }
        return new RepairImpl(config, pc).ruleBasedRepair(code);
    }

    private ScoutAgentTask taskFor(ScoutPromptContext context) {
        ScoutPromptMode mode = context == null ? null : context.getMode();
        if (mode == ScoutPromptMode.ERROR || mode == ScoutPromptMode.ERROR_CONSTR) {
            return ScoutAgentTask.ERROR_REPAIR;
        }
        if (mode == ScoutPromptMode.GUIDE_COVERAGE
                || mode == ScoutPromptMode.GUIDE_COVERAGE_NO_BRANCH
                || mode == ScoutPromptMode.GUIDE_COVERAGE_CALLERS) {
            return ScoutAgentTask.COVERAGE_REPAIR;
        }
        return ScoutAgentTask.TEST_GENERATION;
    }

    private void recordCoverage(ScoutMethodState methodState,
                                String attemptKey,
                                ScoutCoverageResult result,
                                String code) {
        if (methodState == null || result == null) {
            return;
        }

        List<String> uncoveredRegions = uncoveredRegionCodes(result);
        if (!result.isComplete() && uncoveredRegions.isEmpty()) {
            uncoveredRegions.add(fallbackUncoveredRegion(result));
        }
        double score = result.score();
        if (score < 0.0) {
            return;
        }
        new ScoutCoverageState(methodState).recordAttempt(attemptKey, uncoveredRegions, score, code);
    }

    private void cacheAcceptedTest(ScoutClassState classState,
                                   ScoutMethodState methodState,
                                   String attemptKey,
                                   String code) {
        if (classState != null && classState.getCachedTestsByAttempt() != null) {
            classState.getCachedTestsByAttempt().put(attemptKey, code);
        }
        recordTaggedScenarioAttempts(methodState, attemptKey, code);
    }

    private boolean exportAcceptedTest(String code,
                                       String fullTestName,
                                       PromptInfo promptInfo,
                                       int round,
                                       int attempt,
                                       boolean complete) {
        if (complete) {
            return exportCompleteValidCandidate(code, fullTestName, promptInfo);
        } else {
            return archiveIncompleteValidCandidate(code, fullTestName, promptInfo, round, attempt);
        }
    }

    private boolean exportCompleteValidCandidate(String code, String fullTestName, PromptInfo promptInfo) {
        if (config == null || config.getTestOutput() == null || safe(code).trim().isEmpty()
                || safe(fullTestName).trim().isEmpty()) {
            return false;
        }
        Path savePath = promptInfo == null ? null : promptInfo.getTestPath();
        if (savePath == null) {
            savePath = config.getTestOutput().resolve(fullTestName.replace(".", java.io.File.separator) + ".java");
        }
        if (!exportValidatedTest(config, code, savePath, fullTestName, promptInfo)) {
            return false;
        }
        config.markExportedTest();
        if (promptInfo != null && promptInfo.getMethodInfo() != null) {
            config.markMethodWithValidTest(fullClassName(fullTestName, promptInfo), promptInfo.getMethodInfo());
        } else {
            config.markMethodWithValidTest(fullClassName(fullTestName, promptInfo),
                    promptInfo == null ? "" : promptInfo.getMethodSignature(),
                    promptInfo == null ? "" : promptInfo.getMethodName());
        }
        return true;
    }

    private String fullClassName(String fullTestName, PromptInfo promptInfo) {
        String fullClassName = promptInfo == null ? null : promptInfo.getFullClassName();
        if (fullClassName == null || fullClassName.trim().isEmpty()) {
            fullClassName = fullTestName;
        }
        return fullClassName;
    }

    private void recordTaggedScenarioAttempts(ScoutMethodState methodState, String attemptKey, String code) {
        if (methodState == null || methodState.getTaggedScenarioAttemptsByAttempt() == null) {
            return;
        }
        List<ScoutScenarioTag> tags = new ScoutScenarioTagExtractor().extract(code);
        if (!tags.isEmpty()) {
            methodState.getTaggedScenarioAttemptsByAttempt().put(attemptKey, tags);
        }
    }

    private List<String> uncoveredRegionCodes(ScoutCoverageResult result) {
        if (result.getUncoveredRegions() == null) {
            return Collections.emptyList();
        }
        List<String> codes = new ArrayList<String>();
        for (ScoutUncoveredRegion region : result.getUncoveredRegions()) {
            if (region == null) {
                continue;
            }
            String code = safe(region.getCode()).trim();
            if (code.length() > 0) {
                codes.add(code);
            }
        }
        return codes;
    }

    private String fallbackUncoveredRegion(ScoutCoverageResult result) {
        String methodCode = safe(result.getMethodCode()).trim();
        if (methodCode.length() > 0) {
            return methodCode;
        }
        return "Coverage incomplete: instruction=" + result.getInstructionCoverage()
                + ", branch=" + result.getBranchCoverage()
                + ", line=" + result.getLineCoverage();
    }

    private void recordNoCode(RoundRecord record, int promptTokens, int responseTokens, String response, String code) {
        record.setPromptToken(promptTokens);
        record.setResponseToken(responseTokens);
        record.setResponse(response);
        record.setCode(code);
        record.setHasCode(false);
    }

    private RoundRecord ensureRecord(PromptInfo promptInfo, int round) {
        while (promptInfo.getRecords().size() <= round) {
            promptInfo.addRecord(new RoundRecord(promptInfo.getRecords().size()));
        }
        RoundRecord record = promptInfo.getRecords().get(round);
        if (record == null) {
            record = new RoundRecord(round);
            promptInfo.getRecords().set(round, record);
        }
        record.setRound(round);
        return record;
    }

    private String attemptKey(PromptInfo promptInfo, RoundRecord record) {
        String methodIdentity = safe(promptInfo == null ? "" : promptInfo.getMethodSignature()).trim();
        if (methodIdentity.length() == 0) {
            methodIdentity = safe(promptInfo == null ? "" : promptInfo.getMethodName()).trim();
        }
        if (methodIdentity.length() == 0) {
            methodIdentity = "unknown";
        }
        return "method-" + methodIdentity + "-attempt-" + record.getAttempt() + "-round-" + record.getRound();
    }

    private void ensureDescriptions(PromptInfo promptInfo,
                                    ScoutProjectState projectState,
                                    ScoutClassState classState,
                                    ScoutMethodState methodState) {
        if (descriptionService == null) {
            return;
        }
        descriptionService.ensureDescriptions(promptInfo.getClassInfo(), promptInfo.getMethodInfo(),
                projectState, classState, methodState);
    }

    private ScoutProjectState loadProjectState() {
        try {
            return store == null ? new ScoutProjectState() : store.loadProjectState();
        } catch (IOException e) {
            warn("SCOUT failed to load project state: " + e.getMessage());
            return new ScoutProjectState();
        }
    }

    private ScoutClassState loadClassState(String fullClassName) {
        try {
            return store == null ? new ScoutClassState() : store.loadClassState(safe(fullClassName));
        } catch (IOException e) {
            warn("SCOUT failed to load class state: " + e.getMessage());
            return new ScoutClassState();
        }
    }

    private ScoutMethodState loadMethodState(String fullClassName, String methodSignature) {
        try {
            return store == null ? new ScoutMethodState() : store.loadMethodState(safe(fullClassName), safe(methodSignature));
        } catch (IOException e) {
            warn("SCOUT failed to load method state: " + e.getMessage());
            return new ScoutMethodState();
        }
    }

    private void saveStates(ScoutClassState classState, ScoutMethodState methodState) {
        if (store == null) {
            return;
        }
        try {
            store.saveClassState(classState);
        } catch (IOException e) {
            warn("SCOUT failed to save class state: " + e.getMessage());
        }
        try {
            store.saveMethodState(methodState);
        } catch (IOException e) {
            warn("SCOUT failed to save method state: " + e.getMessage());
        }
    }

    private int safeInteger(Integer value, int fallback) {
        return value == null ? fallback : value.intValue();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void info(String message) {
        if (config != null && config.getLogger() != null) {
            config.getLogger().info(message);
        }
    }

    private void debug(String message) {
        if (config != null && config.getLogger() != null) {
            config.getLogger().debug(message);
        }
    }

    private void warn(String message) {
        if (config != null && config.getLogger() != null) {
            config.getLogger().warn(message);
        }
    }

    private static DescriptionService descriptionService(Config config, ScoutStateStore store) {
        if (store == null) {
            return new NoOpDescriptionService();
        }
        return new RealDescriptionService(config, store);
    }

    interface DescriptionService {
        void ensureDescriptions(ClassInfo classInfo,
                                MethodInfo methodInfo,
                                ScoutProjectState projectState,
                                ScoutClassState classState,
                                ScoutMethodState methodState);
    }

    interface PromptRenderer {
        String renderSystem(ScoutPromptContext context);

        String renderUser(ScoutPromptContext context);
    }

    interface CoverageAnalyzer {
        ScoutCoverageResult analyze(String code, String fullTestName, PromptInfo promptInfo);
    }

    interface TokenChecker {
        boolean isExceedMaxTokens(List<ChatMessage> prompt);
    }

    private static class NoOpDescriptionService implements DescriptionService {
        @Override
        public void ensureDescriptions(ClassInfo classInfo, MethodInfo methodInfo, ScoutProjectState projectState,
                                       ScoutClassState classState, ScoutMethodState methodState) {
        }
    }

    private static class RealDescriptionService implements DescriptionService {
        private final ScoutDescriptionService delegate;

        private RealDescriptionService(Config config, ScoutStateStore store) {
            this.delegate = new ScoutDescriptionService(config, store);
        }

        @Override
        public void ensureDescriptions(ClassInfo classInfo, MethodInfo methodInfo, ScoutProjectState projectState,
                                       ScoutClassState classState, ScoutMethodState methodState) {
            delegate.ensureDescriptions(classInfo, methodInfo, projectState, classState, methodState);
        }
    }

    private static class RealPromptRenderer implements PromptRenderer {
        private final ScoutPromptRenderer delegate;

        private RealPromptRenderer(ScoutPromptRenderer delegate) {
            this.delegate = delegate;
        }

        @Override
        public String renderSystem(ScoutPromptContext context) {
            return SYSTEM_PROMPT;
        }

        @Override
        public String renderUser(ScoutPromptContext context) {
            return delegate.renderUser(context);
        }
    }

    private static class RealCoverageAnalyzer implements CoverageAnalyzer {
        private final ScoutCoverageAnalyzer delegate;

        private RealCoverageAnalyzer(ScoutCoverageAnalyzer delegate) {
            this.delegate = delegate;
        }

        @Override
        public ScoutCoverageResult analyze(String code, String fullTestName, PromptInfo promptInfo) {
            return delegate.analyze(code, fullTestName, promptInfo);
        }
    }

    private static class RealTokenChecker implements TokenChecker {
        private final Config config;

        private RealTokenChecker(Config config) {
            this.config = config;
        }

        @Override
        public boolean isExceedMaxTokens(List<ChatMessage> prompt) {
            return config != null && MethodRunner.isExceedMaxTokens(config.getMaxPromptTokens(), prompt);
        }
    }
}
