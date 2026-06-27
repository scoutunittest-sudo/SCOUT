package zju.cst.aces.coverage;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.status.CoverageStatusWindow;
import zju.cst.aces.status.StatusOutput;
import zju.cst.aces.status.StatusWindow;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

public class CoverageSummaryReporter {
    private static final long DEFAULT_TEST_TIMEOUT_SECONDS = 120L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String RESUME_SUCCESS = "SUCCESS";
    private static final String RESUME_FAILED = "FAILED";
    private static final String RESUME_TIMEOUT = "TIMEOUT";

    private final Config config;
    private final List<CompiledTestSource> compiledTests = new ArrayList<>();
    private final List<InvalidTestSource> invalidTests = new ArrayList<>();
    private final StatusWindow statusWindow = new StatusWindow();
    private final CoverageStatusWindow coverageStatusWindow = new CoverageStatusWindow();
    private final long testTimeout;
    private final TimeUnit testTimeoutUnit;

    public CoverageSummaryReporter(Config config) {
        this(config, DEFAULT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    CoverageSummaryReporter(Config config, long testTimeout, TimeUnit testTimeoutUnit) {
        this.config = config;
        this.testTimeout = testTimeout;
        this.testTimeoutUnit = testTimeoutUnit == null ? TimeUnit.SECONDS : testTimeoutUnit;
    }

    public CoverageSummary report() {
        long startNanos = System.nanoTime();
        Config.ResourceLease lease = null;
        ProgressSnapshot progressSnapshot = captureProgress();
        try {
            if (config != null) {
                lease = config.acquireResource("coverage");
            }
            resetCoverageOnlyCounters();
            compileTestSources();
            writeInvalidTestLogs();
            CoverageSummary summary = analyze();
            if (config != null) {
                config.setFullyCoveredMethodCount(summary.getFullyCoveredMethodCount());
            }
            restoreProgressAfterCoverage(progressSnapshot);
            lease = finishCoverageTiming(lease, startNanos);
            writeSummary(summary);
            logSummary(summary);
            return summary;
        } catch (RuntimeException e) {
            restoreProgressAfterCoverage(progressSnapshot);
            finishCoverageTiming(lease, startNanos);
            throw e;
        }
    }

    private Config.ResourceLease finishCoverageTiming(Config.ResourceLease lease, long startNanos) {
        if (lease != null) {
            lease.close();
        }
        if (config != null) {
            config.recordTimingSince("coverage", startNanos);
        }
        return null;
    }

    public void reportIfEnabled() {
        if (config == null || !config.isReportCoverage()) {
            return;
        }
        try {
            report();
        } catch (Exception e) {
            StatusOutput.out().println("Coverage summary failed: " + e.getMessage());
            if (config.getLogger() != null) {
                config.getLogger().warn("Coverage summary failed: " + e.getMessage());
            }
        }
    }

    private CoverageSummary analyze() {
        try {
            List<String> targetClasses = targetClasses();
            List<String> testClasses = testClasses();
            CoverageSummary emptySummary = new CoverageSummary();
            emptySummary.setTargetClassCount(targetClasses.size());
            emptySummary.setTestClassCount(testClasses.size());
            beginCoverageProgress(testClasses.size());
            if (targetClasses.isEmpty() || testClasses.isEmpty()) {
                applyPercentages(emptySummary);
                printCoverageProgress();
                return emptySummary;
            }

            IRuntime runtime = new LoggerRuntime();
            Instrumenter instrumenter = new Instrumenter(runtime);
            RuntimeData runtimeData = new RuntimeData();
            runtime.startup(runtimeData);
            TestCounts testCounts = new TestCounts();
            ExecutionDataStore executionData = new ExecutionDataStore();
            SessionInfoStore sessionInfos = new SessionInfoStore();
            Map<String, byte[]> instrumentedTargets = instrumentedTargetClasses(targetClasses, instrumenter);
            String targetIdentity = targetIdentity(targetClasses);
            Map<Path, List<CompiledTestSource>> testsByClassRoot = testsByClassRoot();
            for (Map.Entry<Path, List<CompiledTestSource>> entry : testsByClassRoot.entrySet()) {
                executeTestClassesIndividually(entry.getKey(), entry.getValue(), instrumentedTargets,
                        runtimeData, executionData, sessionInfos, targetIdentity, testCounts);
            }
            runtime.shutdown();

            CoverageBuilder coverageBuilder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(executionData, coverageBuilder);
            for (String targetClass : targetClasses) {
                Path classFile = classFile(targetClass);
                if (Files.exists(classFile)) {
                    try (InputStream input = Files.newInputStream(classFile)) {
                        analyzer.analyzeClass(input, targetClass);
                    }
                }
            }

            CoverageSummary summary = summarize(coverageBuilder, targetClasses, testClasses, testCounts);
            applyPercentages(summary);
            return summary;
        } catch (Exception e) {
            throw new RuntimeException("In CoverageSummaryReporter.analyze: " + e.getMessage(), e);
        }
    }

    private Map<String, byte[]> instrumentedTargetClasses(List<String> targetClasses,
                                                          Instrumenter instrumenter) throws Exception {
        Map<String, byte[]> instrumentedTargets = new LinkedHashMap<>();
        for (String targetClass : targetClasses) {
            Path classFile = classFile(targetClass);
            if (Files.exists(classFile)) {
                try (InputStream input = Files.newInputStream(classFile)) {
                    instrumentedTargets.put(targetClass, instrumenter.instrument(input, targetClass));
                }
            }
        }
        return instrumentedTargets;
    }

    private Map<Path, List<CompiledTestSource>> testsByClassRoot() {
        Map<Path, List<CompiledTestSource>> testsByClassRoot = new LinkedHashMap<>();
        for (CompiledTestSource compiledTest : compiledTests) {
            List<CompiledTestSource> tests = testsByClassRoot.get(compiledTest.getClassRoot());
            if (tests == null) {
                tests = new ArrayList<>();
                testsByClassRoot.put(compiledTest.getClassRoot(), tests);
            }
            tests.add(compiledTest);
        }
        return testsByClassRoot;
    }

    private LauncherDiscoveryRequest request(ClassLoader classLoader, List<String> testClasses) throws ClassNotFoundException {
        List<DiscoverySelector> selectors = new ArrayList<>();
        for (String testClass : testClasses) {
            selectors.add(DiscoverySelectors.selectClass(classLoader.loadClass(testClass)));
        }
        return LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .configurationParameter("junit.jupiter.execution.timeout.mode", "enabled")
                .configurationParameter("junit.jupiter.execution.timeout.default", junitTimeoutValue())
                .build();
    }

    private void executeTestClassesIndividually(Path classRoot, List<CompiledTestSource> testClasses,
                                                Map<String, byte[]> instrumentedTargets,
                                                RuntimeData runtimeData,
                                                ExecutionDataStore executionData,
                                                SessionInfoStore sessionInfos,
                                                String targetIdentity,
                                                TestCounts testCounts) throws Exception {
        for (CompiledTestSource testClass : testClasses) {
            CoverageResumeEntry checkpoint = validResumeEntry(testClass, targetIdentity);
            if (checkpoint != null) {
                applyResumeEntry(checkpoint, executionData, sessionInfos, testCounts);
                markCoverageItemsCompleted(1);
                printCoverageProgress();
                continue;
            }
            try (CoverageClassLoader classLoader = coverageClassLoader(classRoot, instrumentedTargets)) {
                TestExecutionSummary summary = executeTestsWithTimeout(classLoader,
                        Collections.singletonList(testClass.getClassName()), testClass.getClassName());
                if (summary == null) {
                    testCounts.addTimedOutTest();
                    recordCoverageTimeout(testClass.getClassName());
                    writeResumeEntry(testClass, timeoutResumeEntry(testClass, targetIdentity));
                } else {
                    ExecutionDataStore testExecutionData = new ExecutionDataStore();
                    SessionInfoStore testSessionInfos = new SessionInfoStore();
                    runtimeData.collect(testExecutionData, testSessionInfos, false);
                    testExecutionData = copyExecutionData(testExecutionData);
                    runtimeData.reset();
                    testExecutionData.accept(executionData);
                    testSessionInfos.accept(sessionInfos);
                    testCounts.add(summary);
                    recordCoverageOnlyTestResults(summary);
                    writeResumeEntry(testClass,
                            completedResumeEntry(testClass, targetIdentity, summary, testExecutionData, testSessionInfos));
                }
            } catch (Throwable t) {
                if (isFatalThrowable(t)) {
                    throw t;
                }
                runtimeData.reset();
                testCounts.addFailedTest();
                recordCoverageExecutionFailure(testClass, t);
                writeResumeEntry(testClass, failedResumeEntry(testClass, targetIdentity, t));
            } finally {
                markCoverageItemsCompleted(1);
                printCoverageProgress();
            }
        }
    }

    private ExecutionDataStore copyExecutionData(ExecutionDataStore source) {
        ExecutionDataStore copy = new ExecutionDataStore();
        for (ExecutionData data : source.getContents()) {
            copy.put(new ExecutionData(data.getId(), data.getName(),
                    Arrays.copyOf(data.getProbes(), data.getProbes().length)));
        }
        return copy;
    }

    private CoverageClassLoader coverageClassLoader(Path classRoot,
                                                    Map<String, byte[]> instrumentedTargets) throws Exception {
        CoverageClassLoader classLoader = new CoverageClassLoader(
                classPathUrls(classRoot), getClass().getClassLoader());
        for (Map.Entry<String, byte[]> target : instrumentedTargets.entrySet()) {
            classLoader.addDefinition(target.getKey(), target.getValue());
        }
        return classLoader;
    }

    private CoverageResumeEntry validResumeEntry(CompiledTestSource testClass, String targetIdentity) {
        if (config == null || !config.isCoverageOnlyMode()) {
            return null;
        }
        CoverageResumeEntry entry = readResumeManifest().get(testClass.getClassName());
        if (entry == null || !testClass.getClassName().equals(entry.getClassName())) {
            return null;
        }
        if (!targetIdentity.equals(entry.getTargetIdentity())) {
            return null;
        }
        if (!sourceIdentity(testClass).equals(entry.getSourceIdentity())) {
            return null;
        }
        if (RESUME_TIMEOUT.equals(entry.getStatus())) {
            return entry;
        }
        if (!RESUME_SUCCESS.equals(entry.getStatus()) && !RESUME_FAILED.equals(entry.getStatus())) {
            return null;
        }
        return Files.exists(resumeExecutionDataPath(entry)) ? entry : null;
    }

    private void applyResumeEntry(CoverageResumeEntry entry,
                                  ExecutionDataStore executionData,
                                  SessionInfoStore sessionInfos,
                                  TestCounts testCounts) throws Exception {
        if (!RESUME_TIMEOUT.equals(entry.getStatus())) {
            readExecutionData(resumeExecutionDataPath(entry), executionData, sessionInfos);
        }
        testCounts.add(entry.getTestsFound(), entry.getTestsSucceeded(), entry.getTestsFailed());
        if (RESUME_FAILED.equals(entry.getStatus()) && !isBlank(entry.getFailureDiagnostics())) {
            recordInvalidTestSource(pathOrNull(entry.getSourcePath()), entry.getClassName(),
                    entry.getFailureDiagnostics(), false);
        }
        if (config != null && config.isCoverageOnlyMode()) {
            incrementValidCoverageTests(entry.getTestsSucceeded());
            incrementRuntimeErrors(entry.getTestsFailed());
            if (RESUME_TIMEOUT.equals(entry.getStatus())) {
                config.markTimeout();
            }
        }
    }

    private CoverageResumeEntry completedResumeEntry(CompiledTestSource testClass,
                                                     String targetIdentity,
                                                     TestExecutionSummary summary,
                                                     ExecutionDataStore executionData,
                                                     SessionInfoStore sessionInfos) throws Exception {
        CoverageResumeEntry entry = baseResumeEntry(testClass, targetIdentity);
        entry.setStatus(summary.getTestsFailedCount() == 0 ? RESUME_SUCCESS : RESUME_FAILED);
        entry.setTestsFound(summary.getTestsFoundCount());
        entry.setTestsSucceeded(summary.getTestsSucceededCount());
        entry.setTestsFailed(summary.getTestsFailedCount());
        entry.setExecutionDataFile(sanitizePathSegment(testClass.getClassName()) + ".exec");
        writeExecutionData(resumeExecutionDataPath(entry), executionData, sessionInfos);
        return entry;
    }

    private CoverageResumeEntry timeoutResumeEntry(CompiledTestSource testClass,
                                                   String targetIdentity) {
        CoverageResumeEntry entry = baseResumeEntry(testClass, targetIdentity);
        entry.setStatus(RESUME_TIMEOUT);
        entry.setTestsFound(1);
        entry.setTestsSucceeded(0);
        entry.setTestsFailed(1);
        entry.setExecutionDataFile("");
        return entry;
    }

    private CoverageResumeEntry failedResumeEntry(CompiledTestSource testClass,
                                                  String targetIdentity,
                                                  Throwable throwable) throws Exception {
        CoverageResumeEntry entry = baseResumeEntry(testClass, targetIdentity);
        entry.setStatus(RESUME_FAILED);
        entry.setTestsFound(1);
        entry.setTestsSucceeded(0);
        entry.setTestsFailed(1);
        entry.setExecutionDataFile(sanitizePathSegment(testClass.getClassName()) + ".failed.exec");
        entry.setFailureDiagnostics(throwableDiagnostics(throwable));
        writeExecutionData(resumeExecutionDataPath(entry), new ExecutionDataStore(), new SessionInfoStore());
        return entry;
    }

    private CoverageResumeEntry baseResumeEntry(CompiledTestSource testClass,
                                                String targetIdentity) {
        CoverageResumeEntry entry = new CoverageResumeEntry();
        entry.setClassName(testClass.getClassName());
        entry.setSourcePath(testClass.getSourcePath().toString());
        entry.setSourceIdentity(sourceIdentity(testClass));
        entry.setTargetIdentity(targetIdentity);
        return entry;
    }

    private void writeResumeEntry(CompiledTestSource testClass,
                                  CoverageResumeEntry entry) {
        if (config == null || !config.isCoverageOnlyMode() || entry == null) {
            return;
        }
        try {
            Map<String, CoverageResumeEntry> manifest = readResumeManifest();
            manifest.put(testClass.getClassName(), entry);
            writeResumeManifest(manifest);
        } catch (Exception e) {
            if (config.getLogger() != null) {
                config.getLogger().warn("Failed to write coverage resume checkpoint for "
                        + testClass.getClassName() + ": " + e.getMessage());
            }
        }
    }

    private Map<String, CoverageResumeEntry> readResumeManifest() {
        Path manifest = resumeManifestPath();
        if (manifest == null || !Files.exists(manifest)) {
            return new LinkedHashMap<>();
        }
        try {
            Type type = new TypeToken<Map<String, CoverageResumeEntry>>() {}.getType();
            Map<String, CoverageResumeEntry> entries = GSON.fromJson(
                    new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8), type);
            return entries == null ? new LinkedHashMap<>() : entries;
        } catch (Exception e) {
            if (config != null && config.getLogger() != null) {
                config.getLogger().warn("Ignoring unreadable coverage resume manifest: " + e.getMessage());
            }
            return new LinkedHashMap<>();
        }
    }

    private void writeResumeManifest(Map<String, CoverageResumeEntry> entries) throws Exception {
        Path manifest = resumeManifestPath();
        if (manifest == null) {
            return;
        }
        Files.createDirectories(manifest.getParent());
        Path tmp = manifest.resolveSibling(manifest.getFileName().toString() + ".tmp");
        Files.write(tmp, GSON.toJson(entries).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeExecutionData(Path path,
                                    ExecutionDataStore executionData,
                                    SessionInfoStore sessionInfos) throws Exception {
        Files.createDirectories(path.getParent());
        try (OutputStream output = Files.newOutputStream(path)) {
            ExecutionDataWriter writer = new ExecutionDataWriter(output);
            sessionInfos.accept(writer);
            executionData.accept(writer);
            writer.flush();
        }
    }

    private void readExecutionData(Path path,
                                   ExecutionDataStore executionData,
                                   SessionInfoStore sessionInfos) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            ExecutionDataReader reader = new ExecutionDataReader(input);
            reader.setExecutionDataVisitor(executionData);
            reader.setSessionInfoVisitor(sessionInfos);
            while (reader.read()) {
                // read all blocks
            }
        }
    }

    private Path resumeExecutionDataPath(CoverageResumeEntry entry) {
        return resumeRoot().resolve("execution-data").resolve(entry.getExecutionDataFile());
    }

    private Path resumeManifestPath() {
        return resumeRoot().resolve("manifest.json");
    }

    private Path resumeRoot() {
        Path base = config == null ? null : config.getTmpOutput();
        if (base == null && config != null && config.getTestOutput() != null) {
            base = config.getTestOutput().resolve("chatunitest-info");
        }
        if (base == null) {
            base = new File(System.getProperty("java.io.tmpdir")).toPath().resolve("chatunitest-info");
        }
        return base.resolve("coverage-resume");
    }

    private String sourceIdentity(CompiledTestSource testClass) {
        return fileIdentity(testClass.getSourcePath());
    }

    private String targetIdentity(List<String> targetClasses) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String targetClass : targetClasses) {
            digest.update(targetClass.getBytes(StandardCharsets.UTF_8));
            Path path = classFile(targetClass);
            if (Files.exists(path)) {
                digest.update(Files.readAllBytes(path));
            }
        }
        return hex(digest.digest());
    }

    private String fileIdentity(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            if (Files.exists(path)) {
                digest.update(Files.readAllBytes(path));
            }
            return hex(digest.digest());
        } catch (Exception e) {
            return path == null ? "" : path.toAbsolutePath().normalize().toString();
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private String junitTimeoutValue() {
        if (testTimeoutUnit == TimeUnit.NANOSECONDS) {
            return testTimeout + " ns";
        }
        if (testTimeoutUnit == TimeUnit.MICROSECONDS) {
            return testTimeoutUnit.toNanos(testTimeout) + " ns";
        }
        if (testTimeoutUnit == TimeUnit.MILLISECONDS) {
            return testTimeout + " ms";
        }
        if (testTimeoutUnit == TimeUnit.SECONDS) {
            return testTimeout + " s";
        }
        if (testTimeoutUnit == TimeUnit.MINUTES) {
            return testTimeout + " m";
        }
        if (testTimeoutUnit == TimeUnit.HOURS) {
            return testTimeout + " h";
        }
        return testTimeoutUnit.toDays(testTimeout) + " d";
    }

    private TestExecutionSummary executeTestsWithTimeout(final ClassLoader classLoader,
                                                        final List<String> testClasses,
                                                        String timeoutLabel) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(coverageTimeoutThreadFactory(timeoutLabel));
        Future<TestExecutionSummary> future = executor.submit(new Callable<TestExecutionSummary>() {
            @Override
            public TestExecutionSummary call() throws Exception {
                LauncherDiscoveryRequest request = request(classLoader, testClasses);
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                Launcher launcher = LauncherFactory.create();
                launcher.registerTestExecutionListeners(listener);
                launcher.execute(request);
                return listener.getSummary();
            }
        });
        try {
            return future.get(testTimeout, testTimeoutUnit);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private ThreadFactory coverageTimeoutThreadFactory(final String timeoutLabel) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "chatunitest-coverage-" + sanitizePathSegment(timeoutLabel));
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private CoverageSummary summarize(CoverageBuilder coverageBuilder, List<String> targetClasses,
                                      List<String> testClasses, TestCounts testCounts) {
        Set<String> targetClassNames = new LinkedHashSet<>(targetClasses);
        CoverageSummary summary = new CoverageSummary();
        summary.setTargetClassCount(targetClasses.size());
        summary.setTestClassCount(testClasses.size());
        summary.setTestsFound(testCounts.getTestsFound());
        summary.setTestsSucceeded(testCounts.getTestsSucceeded());
        summary.setTestsFailed(testCounts.getTestsFailed());

        for (IClassCoverage classCoverage : coverageBuilder.getClasses()) {
            if (!targetClassNames.contains(classCoverage.getName().replace("/", "."))) {
                continue;
            }
            summary.setInstructionCovered(summary.getInstructionCovered() + classCoverage.getInstructionCounter().getCoveredCount());
            summary.setInstructionTotal(summary.getInstructionTotal() + classCoverage.getInstructionCounter().getTotalCount());
            summary.setBranchCovered(summary.getBranchCovered() + classCoverage.getBranchCounter().getCoveredCount());
            summary.setBranchTotal(summary.getBranchTotal() + classCoverage.getBranchCounter().getTotalCount());
            summary.setLineCovered(summary.getLineCovered() + classCoverage.getLineCounter().getCoveredCount());
            summary.setLineTotal(summary.getLineTotal() + classCoverage.getLineCounter().getTotalCount());
            recordFullyCoveredMethods(summary, classCoverage);
        }
        summary.setFullyCoveredMethodCount(summary.getFullyCoveredMethods().size());
        return summary;
    }

    private void recordFullyCoveredMethods(CoverageSummary summary, IClassCoverage classCoverage) {
        String className = classCoverage.getName().replace("/", ".");
        for (IMethodCoverage methodCoverage : classCoverage.getMethods()) {
            if (!isReportableMethod(methodCoverage)) {
                continue;
            }
            long lineCovered = methodCoverage.getLineCounter().getCoveredCount();
            long lineTotal = methodCoverage.getLineCounter().getTotalCount();
            if (lineTotal > 0 && lineCovered == lineTotal) {
                FullyCoveredMethod method = new FullyCoveredMethod();
                method.setClassName(className);
                method.setMethodName(methodCoverage.getName());
                method.setMethodSignature(methodCoverage.getDesc());
                method.setLineCovered(lineCovered);
                method.setLineTotal(lineTotal);
                method.setLineCoverage(CoverageSummary.percentage(lineCovered, lineTotal));
                method.setFirstLine(methodCoverage.getFirstLine());
                method.setLastLine(methodCoverage.getLastLine());
                summary.getFullyCoveredMethods().add(method);
            }
        }
    }

    private boolean isReportableMethod(IMethodCoverage methodCoverage) {
        if (methodCoverage == null || methodCoverage.getName() == null) {
            return false;
        }
        String name = methodCoverage.getName();
        return !name.equals("<init>") && !name.equals("<clinit>");
    }

    private void applyPercentages(CoverageSummary summary) {
        summary.setInstructionCoverage(CoverageSummary.percentage(summary.getInstructionCovered(), summary.getInstructionTotal()));
        summary.setBranchCoverage(CoverageSummary.percentage(summary.getBranchCovered(), summary.getBranchTotal()));
        summary.setLineCoverage(CoverageSummary.percentage(summary.getLineCovered(), summary.getLineTotal()));
    }

    private List<String> targetClasses() throws Exception {
        String configuredClass = config.getCname();
        if (configuredClass != null && !configuredClass.trim().isEmpty()) {
            return resolveConfiguredClass(configuredClass.trim());
        }
        Set<String> classes = new LinkedHashSet<>();
        if (config.getClassNameMapPath() != null && Files.exists(config.getClassNameMapPath())) {
            Type type = new TypeToken<Map<String, List<String>>>() {}.getType();
            Map<String, List<String>> mapping = GSON.fromJson(
                    new String(Files.readAllBytes(config.getClassNameMapPath()), StandardCharsets.UTF_8), type);
            if (mapping != null) {
                for (List<String> names : mapping.values()) {
                    if (names != null) {
                        classes.addAll(names);
                    }
                }
            }
        }
        return new ArrayList<>(classes);
    }

    private List<String> resolveConfiguredClass(String configuredClass) throws Exception {
        if (configuredClass.contains(".")) {
            List<String> result = new ArrayList<>();
            result.add(configuredClass);
            return result;
        }
        if (config.getClassNameMapPath() != null && Files.exists(config.getClassNameMapPath())) {
            Type type = new TypeToken<Map<String, List<String>>>() {}.getType();
            Map<String, List<String>> mapping = GSON.fromJson(
                    new String(Files.readAllBytes(config.getClassNameMapPath()), StandardCharsets.UTF_8), type);
            if (mapping != null && mapping.containsKey(configuredClass)) {
                return new ArrayList<>(mapping.get(configuredClass));
            }
        }
        List<String> fallback = new ArrayList<>();
        fallback.add(configuredClass);
        return fallback;
    }

    private List<String> testClasses() throws Exception {
        Set<String> classes = new LinkedHashSet<>();
        Path sourceRoot = config.getTestOutput();
        if (sourceRoot == null || !Files.exists(sourceRoot)) {
            return new ArrayList<>();
        }
        if (!compiledTests.isEmpty()) {
            List<String> compiledClassNames = new ArrayList<>();
            for (CompiledTestSource compiledTest : compiledTests) {
                compiledClassNames.add(compiledTest.getClassName());
            }
            return compiledClassNames;
        }

        Path classRoot = config.getCompileOutputPath();
        if (classRoot == null || !Files.exists(classRoot)) {
            return new ArrayList<>();
        }
        Files.walk(sourceRoot)
                .filter(path -> Files.isRegularFile(path)
                        && path.getFileName().toString().endsWith(".java"))
                .filter(path -> !isMergedTestSource(sourceRoot, path))
                .forEach(path -> {
                    String className = sourceRoot.relativize(path).toString()
                            .replace(File.separatorChar, '.')
                            .replaceAll("\\.java$", "");
                    if (isTestClassName(className) && Files.exists(classFileForTestClass(classRoot, className))) {
                        classes.add(className);
                    }
                });
        return new ArrayList<>(classes);
    }

    private void compileTestSources() {
        try {
            compiledTests.clear();
            invalidTests.clear();
            Path sourceRoot = config.getTestOutput();
            Path classRoot = config.getCompileOutputPath();
            if (sourceRoot == null || classRoot == null || !Files.exists(sourceRoot)) {
                return;
            }

            List<File> sources = new ArrayList<>();
            Files.walk(sourceRoot)
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !isMergedTestSource(sourceRoot, path))
                    .forEach(path -> sources.add(path.toFile()));
            sources.sort(Comparator.comparing(File::getAbsolutePath));
            if (sources.isEmpty()) {
                return;
            }

            resetCompileOutput(classRoot);
            Files.createDirectories(classRoot);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IllegalStateException("No system Java compiler is available. Run with a JDK, not a JRE.");
            }

            List<TestSourceUnit> testSources = new ArrayList<>();
            for (File source : sources) {
                String className = sourceRoot.relativize(source.toPath()).toString()
                        .replace(File.separatorChar, '.')
                        .replaceAll("\\.java$", "");
                if (!isTestClassName(className)) {
                    continue;
                }
                testSources.add(new TestSourceUnit(source, className,
                        topLevelTypeKeys(source.toPath(), className)));
            }

            List<File> classPath = classPathFiles();
            List<CompileBatch> batches = compileBatches(testSources);
            beginCoverageProgress(testSources.size());
            int batchIndex = 0;
            for (CompileBatch batch : batches) {
                Path batchClassRoot = classRoot.resolve("__coverage-tests").resolve("batch-" + batchIndex++);
                if (!compileBatch(compiler, batch, batchClassRoot, classPath, classRoot)) {
                    deleteRecursively(batchClassRoot);
                    for (TestSourceUnit testSource : batch.getSources()) {
                        compileSingleSource(compiler, testSource, classRoot, classPath);
                        markCoverageItemsCompleted(1);
                        printCoverageProgress();
                    }
                } else {
                    markCoverageItemsCompleted(batch.getSources().size());
                    printCoverageProgress();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("In CoverageSummaryReporter.compileTestSources: " + e.getMessage(), e);
        }
    }

    private List<CompileBatch> compileBatches(List<TestSourceUnit> testSources) {
        List<CompileBatch> batches = new ArrayList<>();
        for (TestSourceUnit testSource : testSources) {
            CompileBatch selected = null;
            for (CompileBatch batch : batches) {
                if (batch.canAdd(testSource)) {
                    selected = batch;
                    break;
                }
            }
            if (selected == null) {
                selected = new CompileBatch();
                batches.add(selected);
            }
            selected.add(testSource);
        }
        return batches;
    }

    private boolean compileBatch(JavaCompiler compiler, CompileBatch batch, Path outputRoot,
                                 List<File> classPath, Path conventionalClassRoot) throws Exception {
        Files.createDirectories(outputRoot);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(outputRoot.toFile()));
            fileManager.setLocation(StandardLocation.CLASS_PATH, classPath);
            Boolean result = compiler.getTask(null, fileManager, diagnostics, null, null,
                    fileManager.getJavaFileObjectsFromFiles(batch.getSourceFiles())).call();
            if (!Boolean.TRUE.equals(result)) {
                return false;
            }
            for (TestSourceUnit testSource : batch.getSources()) {
                addCompiledTestIfPresent(testSource, outputRoot, conventionalClassRoot);
            }
            return true;
        }
    }

    private void compileSingleSource(JavaCompiler compiler, TestSourceUnit testSource,
                                     Path classRoot, List<File> classPath) throws Exception {
        Path isolatedClassRoot = classRoot.resolve("__coverage-tests")
                .resolve("single-" + sanitizePathSegment(testSource.getClassName()));
        Files.createDirectories(isolatedClassRoot);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT,
                    Collections.singletonList(isolatedClassRoot.toFile()));
            fileManager.setLocation(StandardLocation.CLASS_PATH, classPath);
            Boolean result = compiler.getTask(null, fileManager, diagnostics, null, null,
                    fileManager.getJavaFileObjectsFromFiles(
                            Collections.singletonList(testSource.getSource()))).call();
            if (!Boolean.TRUE.equals(result)) {
                recordInvalidTestSource(testSource.getSource().toPath(),
                        testSource.getClassName(), formatDiagnostics(diagnostics));
                return;
            }
            addCompiledTestIfPresent(testSource, isolatedClassRoot, classRoot);
        } catch (Exception e) {
            recordInvalidTestSource(testSource.getSource().toPath(),
                    testSource.getClassName(), exceptionMessage(e));
        }
    }

    private void addCompiledTestIfPresent(TestSourceUnit testSource, Path isolatedClassRoot,
                                          Path conventionalClassRoot) throws Exception {
        String className = testSource.getClassName();
        Path isolatedTestClassFile = classFileForTestClass(isolatedClassRoot, className);
        if (Files.exists(isolatedTestClassFile)) {
            Path conventionalTestClassFile = classFileForTestClass(conventionalClassRoot, className);
            Files.createDirectories(conventionalTestClassFile.getParent());
            Files.copy(isolatedTestClassFile, conventionalTestClassFile,
                    StandardCopyOption.REPLACE_EXISTING);
            compiledTests.add(new CompiledTestSource(className, isolatedClassRoot, testSource.getSource().toPath()));
        }
    }

    private Set<String> topLevelTypeKeys(Path sourcePath, String className) {
        Set<String> keys = new LinkedHashSet<>();
        String fallbackPackage = packageName(className);
        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(
                    new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8));
            String declaredPackage = compilationUnit.getPackageDeclaration().isPresent()
                    ? compilationUnit.getPackageDeclaration().get().getNameAsString()
                    : fallbackPackage;
            for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
                keys.add(declaredPackage + "." + type.getNameAsString());
            }
        } catch (Exception e) {
            keys.add(className);
        }
        if (keys.isEmpty()) {
            keys.add(className);
        }
        return keys;
    }

    private String packageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? "" : className.substring(0, lastDot);
    }

    private String sanitizePathSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void beginCoverageProgress(int total) {
        if (config == null) {
            return;
        }
        config.resetStatusProgress(total, "Coverage", config.getTestOutput());
        printCoverageProgress();
    }

    private void markCoverageItemsCompleted(int count) {
        if (config == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            config.markMethodCompleted();
        }
    }

    private void printCoverageProgress() {
        if (config != null && config.isStatusOnlyOutput()) {
            if (config.isCoverageOnlyMode()) {
                coverageStatusWindow.print(config);
            } else {
                statusWindow.print(config);
            }
        }
    }

    private void resetCoverageOnlyCounters() {
        if (config == null || !config.isCoverageOnlyMode()) {
            return;
        }
        if (config.getValidCoverageTestCount() != null) {
            config.getValidCoverageTestCount().set(0);
        }
        if (config.getCompilationErrorCount() != null) {
            config.getCompilationErrorCount().set(0);
        }
        if (config.getRuntimeErrorCount() != null) {
            config.getRuntimeErrorCount().set(0);
        }
    }

    private void recordCoverageOnlyTestResults(TestExecutionSummary summary) {
        if (config == null || !config.isCoverageOnlyMode() || summary == null) {
            return;
        }
        incrementValidCoverageTests(summary.getTestsSucceededCount());
        incrementRuntimeErrors(summary.getTestsFailedCount());
    }

    private void recordCoverageTimeout(String testClass) {
        if (config != null) {
            config.markRuntimeError();
            config.markTimeout();
            if (config.getLogger() != null) {
                config.getLogger().warn("Coverage test timed out after "
                        + testTimeout + " " + testTimeoutUnit.toString().toLowerCase()
                        + ": " + testClass);
            }
        }
    }

    private void recordCoverageExecutionFailure(CompiledTestSource testClass, Throwable throwable) {
        String className = testClass == null ? "" : testClass.getClassName();
        Path sourcePath = testClass == null ? null : testClass.getSourcePath();
        recordInvalidTestSource(sourcePath, className, throwableDiagnostics(throwable), false);
        if (config != null) {
            config.markRuntimeError();
        }
    }

    private void incrementValidCoverageTests(long count) {
        if (config == null) {
            return;
        }
        for (long i = 0; i < count; i++) {
            config.markValidCoverageTest();
        }
    }

    private void incrementRuntimeErrors(long count) {
        if (config == null) {
            return;
        }
        for (long i = 0; i < count; i++) {
            config.markRuntimeError();
        }
    }

    private ProgressSnapshot captureProgress() {
        if (config == null || config.isCoverageOnlyMode()) {
            return null;
        }
        return new ProgressSnapshot(
                config.getJobCount() == null ? 0 : config.getJobCount().get(),
                config.getCompletedJobCount() == null ? 0 : config.getCompletedJobCount().get(),
                config.getCurrentStatusStep(),
                config.getCurrentStatusMethod(),
                config.getStatusStartTimeMillis(),
                config.getStatusOutputPath());
    }

    private void restoreProgressAfterCoverage(ProgressSnapshot snapshot) {
        if (config == null || snapshot == null) {
            return;
        }
        snapshot.restore(config);
    }

    private void recordInvalidTestSource(Path sourcePath, String className, String diagnostics) {
        recordInvalidTestSource(sourcePath, className, diagnostics, true);
    }

    private void recordInvalidTestSource(Path sourcePath, String className, String diagnostics,
                                         boolean markCompilationError) {
        InvalidTestSource invalid = new InvalidTestSource();
        invalid.setClassName(className);
        invalid.setSourcePath(sourcePath == null ? "" : sourcePath.toString());
        invalid.setDiagnostics(diagnostics == null ? "" : diagnostics);
        invalidTests.add(invalid);
        if (markCompilationError && config != null && config.isCoverageOnlyMode()) {
            config.markCompilationError();
            printCoverageProgress();
        }
        if (config != null && config.getLogger() != null) {
            config.getLogger().warn("Skipping invalid coverage test source < " + className + " >: "
                    + invalid.getDiagnostics());
        }
        try {
            writeInvalidTestLogs();
        } catch (RuntimeException e) {
            if (config != null && config.getLogger() != null) {
                config.getLogger().warn("Failed to write invalid coverage test source log: " + e.getMessage());
            }
        }
    }

    private String exceptionMessage(Exception e) {
        return throwableMessage(e);
    }

    private String throwableMessage(Throwable e) {
        if (e == null) {
            return "";
        }
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    private String throwableDiagnostics(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Path pathOrNull(String path) {
        return isBlank(path) ? null : new File(path).toPath();
    }

    private boolean isFatalThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof VirtualMachineError || current instanceof ThreadDeath) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void resetCompileOutput(Path classRoot) throws Exception {
        deleteRecursively(classRoot);
    }

    private void deleteRecursively(Path root) throws Exception {
        if (root == null) {
            return;
        }
        if (Files.exists(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }
    }

    private List<File> classPathFiles() {
        LinkedHashSet<File> files = new LinkedHashSet<>();
        addClassPathFile(files, config.getCompileOutputPath());
        if (config.getProject() != null) {
            addClassPathFile(files, config.getProject().getBuildPath());
        }
        addClassPathFiles(files, config.getClassPaths());
        addClassPathFiles(files, config.getDependencyPaths());
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry != null && !entry.trim().isEmpty()) {
                files.add(new File(entry));
            }
        }
        return new ArrayList<>(files);
    }

    private void addClassPathFiles(Set<File> files, List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            if (path != null && !path.trim().isEmpty()) {
                files.add(new File(path));
            }
        }
    }

    private void addClassPathFile(Set<File> files, Path path) {
        if (path != null) {
            files.add(path.toFile());
        }
    }

    private String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        List<String> messages = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            messages.add("line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null));
        }
        return String.join("\n", messages);
    }

    private boolean isTestClassName(String className) {
        return className.endsWith("_Test") || className.endsWith("Test");
    }

    private boolean isMergedTestSource(Path sourceRoot, Path path) {
        if (sourceRoot == null || path == null) {
            return false;
        }
        Path relative = sourceRoot.relativize(path);
        return relative.getNameCount() > 0 && "test_merged".equals(relative.getName(0).toString());
    }

    private Path classFileForTestClass(Path root, String className) {
        return root.resolve(className.replace('.', File.separatorChar) + ".class");
    }

    private URL[] classPathUrls(Path testClassRoot) throws Exception {
        Set<URL> urls = new LinkedHashSet<>();
        addUrl(urls, testClassRoot);
        addUrl(urls, config.getProject().getBuildPath());
        addUrls(urls, config.getClassPaths());
        addUrls(urls, config.getDependencyPaths());
        return urls.toArray(new URL[0]);
    }

    private void addUrls(Set<URL> urls, List<String> paths) throws Exception {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            if (path != null && !path.trim().isEmpty()) {
                addUrl(urls, new File(path).toPath());
            }
        }
    }

    private void addUrl(Set<URL> urls, Path path) throws Exception {
        if (path != null && Files.exists(path)) {
            urls.add(path.toUri().toURL());
        }
    }

    private Path classFile(String targetClass) {
        return config.getProject().getBuildPath().resolve(targetClass.replace('.', File.separatorChar) + ".class");
    }

    private void writeSummary(CoverageSummary summary) {
        try {
            Path jsonOutput = config.getTestOutput().resolve("coverage-summary.json");
            Path textOutput = config.getTestOutput().resolve("coverage-summary.txt");
            Path experimentOutput = config.getTestOutput().resolve("experiment-summary.txt");
            Path fullyCoveredMethodsOutput = config.getTestOutput().resolve("fully_covered_methods.json");
            Files.createDirectories(jsonOutput.getParent());
            Files.write(jsonOutput, GSON.toJson(summary).getBytes(StandardCharsets.UTF_8));
            Files.write(textOutput, formatSummary(summary).getBytes(StandardCharsets.UTF_8));
            Files.write(experimentOutput, formatExperimentSummary(summary).getBytes(StandardCharsets.UTF_8));
            Files.write(fullyCoveredMethodsOutput,
                    GSON.toJson(summary.getFullyCoveredMethods()).getBytes(StandardCharsets.UTF_8));
            writeInvalidTestLogs();
        } catch (Exception e) {
            throw new RuntimeException("In CoverageSummaryReporter.writeSummary: " + e.getMessage(), e);
        }
    }

    private void writeInvalidTestLogs() {
        try {
            if (config == null || config.getTestOutput() == null) {
                return;
            }
            Path invalidTestsJsonOutput = config.getTestOutput().resolve("coverage-invalid-tests.json");
            Path invalidTestsTextOutput = config.getTestOutput().resolve("coverage-invalid-tests.txt");
            Files.createDirectories(invalidTestsJsonOutput.getParent());
            Files.write(invalidTestsJsonOutput, GSON.toJson(invalidTests).getBytes(StandardCharsets.UTF_8));
            Files.write(invalidTestsTextOutput, formatInvalidTests().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("In CoverageSummaryReporter.writeInvalidTestLogs: " + e.getMessage(), e);
        }
    }

    private void logSummary(CoverageSummary summary) {
        String formatted = formatSummary(summary);
        if (config.isStatusOnlyOutput() || config.getLogger() == null) {
            StatusOutput.out().println(formatted);
        }
        if (config.getLogger() == null) {
            return;
        }
        config.getLogger().info(formatted);
    }

    private String formatSummary(CoverageSummary summary) {
        return String.format(
                "\n==========================\n[%s] Coverage Summary\n"
                        + "Output: %s\n"
                        + "Target classes: %d, Test classes: %d, Tests: %d found / %d succeeded / %d failed\n"
                        + "Instruction Coverage: %.2f%% (%d/%d)\n"
                        + "Branch Coverage: %.2f%% (%d/%d)\n"
                        + "Line Coverage: %.2f%% (%d/%d)\n"
                        + "Fully covered methods: %d\n"
                        + "Invalid test sources skipped: %d",
                config.getPluginSign(),
                config.getTestOutput().resolve("coverage-summary.txt"),
                summary.getTargetClassCount(),
                summary.getTestClassCount(),
                summary.getTestsFound(),
                summary.getTestsSucceeded(),
                summary.getTestsFailed(),
                summary.getInstructionCoverage(),
                summary.getInstructionCovered(),
                summary.getInstructionTotal(),
                summary.getBranchCoverage(),
                summary.getBranchCovered(),
                summary.getBranchTotal(),
                summary.getLineCoverage(),
                summary.getLineCovered(),
                summary.getLineTotal(),
                summary.getFullyCoveredMethodCount(),
                invalidTests.size());
    }

    private String formatInvalidTests() {
        if (invalidTests.isEmpty()) {
            return "No invalid coverage test sources skipped.\n";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Invalid coverage test sources skipped: ")
                .append(invalidTests.size())
                .append("\n");
        for (InvalidTestSource invalid : invalidTests) {
            builder.append("\n")
                    .append("Class: ").append(invalid.getClassName()).append("\n")
                    .append("Source: ").append(invalid.getSourcePath()).append("\n")
                    .append("Diagnostics:\n")
                    .append(invalid.getDiagnostics()).append("\n");
        }
        return builder.toString();
    }

    private String formatExperimentSummary(CoverageSummary summary) {
        String base = String.format(
                "ChatUniTest Experiment Summary\n"
                        + "==============================\n"
                        + "Project: %s\n"
                        + "Phase: %s\n"
                        + "Model: %s\n"
                        + "Mode: %s\n"
                        + "Coverage mode: report on\n"
                        + "Output: %s\n"
                        + "\n"
                        + "Progress\n"
                        + "--------\n"
                        + "Methods: %d / %d completed\n"
                        + "Valid methods: %d\n"
                        + "Fully covered methods: %d\n"
                        + "Exported tests: %d\n"
                        + "Compile errors: %d\n"
                        + "Runtime errors: %d\n"
                        + "Timeouts: %d\n"
                        + "LLM attempts: %d\n"
                        + "LLM successes: %d\n"
                        + "\n"
                        + "Achieved Coverage\n"
                        + "-----------------\n"
                        + "Total Coverage: %.2f%% line coverage\n"
                        + "Instruction Coverage: %.2f%% (%d/%d)\n"
                        + "Branch Coverage: %.2f%% (%d/%d)\n"
                        + "Line Coverage: %.2f%% (%d/%d)\n"
                        + "\n"
                        + "Tests: %d found / %d succeeded / %d failed\n"
                        + "Target classes: %d\n"
                        + "Test classes: %d\n",
                projectName(),
                value(config.getPhaseType(), "unknown"),
                modelName(),
                config.isEnableMultithreading() ? "multithreading" : "single-thread",
                config.getTestOutput(),
                config.getCompletedJobCount() == null ? 0 : config.getCompletedJobCount().get(),
                config.getJobCount() == null ? 0 : config.getJobCount().get(),
                config.getValidUnitTestMethodCount() == null ? 0 : config.getValidUnitTestMethodCount().get(),
                summary.getFullyCoveredMethodCount(),
                config.getExportedTestCount() == null ? 0 : config.getExportedTestCount().get(),
                config.getCompilationErrorCount() == null ? 0 : config.getCompilationErrorCount().get(),
                config.getRuntimeErrorCount() == null ? 0 : config.getRuntimeErrorCount().get(),
                config.getTimeoutCount() == null ? 0 : config.getTimeoutCount().get(),
                config.getLlmCallAttemptCount() == null ? 0 : config.getLlmCallAttemptCount().get(),
                config.getLlmCallSuccessCount() == null ? 0 : config.getLlmCallSuccessCount().get(),
                summary.getLineCoverage(),
                summary.getInstructionCoverage(),
                summary.getInstructionCovered(),
                summary.getInstructionTotal(),
                summary.getBranchCoverage(),
                summary.getBranchCovered(),
                summary.getBranchTotal(),
                summary.getLineCoverage(),
                summary.getLineCovered(),
                summary.getLineTotal(),
                summary.getTestsFound(),
                summary.getTestsSucceeded(),
                summary.getTestsFailed(),
                summary.getTargetClassCount(),
                summary.getTestClassCount());
        String timingProfile = config == null ? "" : config.formatTimingProfile();
        if (timingProfile == null || timingProfile.trim().isEmpty()) {
            return base;
        }
        return base + "\n" + timingProfile;
    }

    private String projectName() {
        if (config == null || config.getProject() == null || config.getProject().getBasedir() == null) {
            return "unknown";
        }
        return config.getProject().getBasedir().getAbsolutePath();
    }

    private String modelName() {
        if (config == null) {
            return "unknown";
        }
        if (config.getModelName() != null && !config.getModelName().trim().isEmpty()) {
            return config.getModelName().trim();
        }
        if (config.getModel() != null) {
            return config.getModel().getModelName();
        }
        return "unknown";
    }

    private String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    static class CoverageClassLoader extends URLClassLoader {
        private final Map<String, byte[]> definitions = new java.util.HashMap<>();

        CoverageClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        void addDefinition(String name, byte[] bytes) {
            definitions.put(name, bytes);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (definitions.containsKey(name)) {
                return findClass(name);
            }
            return super.loadClass(name);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }

    private static class CompiledTestSource {
        private final String className;
        private final Path classRoot;
        private final Path sourcePath;

        private CompiledTestSource(String className, Path classRoot, Path sourcePath) {
            this.className = className;
            this.classRoot = classRoot;
            this.sourcePath = sourcePath;
        }

        private String getClassName() {
            return className;
        }

        private Path getClassRoot() {
            return classRoot;
        }

        private Path getSourcePath() {
            return sourcePath;
        }
    }

    private static class TestSourceUnit {
        private final File source;
        private final String className;
        private final Set<String> topLevelTypeKeys;

        private TestSourceUnit(File source, String className, Set<String> topLevelTypeKeys) {
            this.source = source;
            this.className = className;
            this.topLevelTypeKeys = topLevelTypeKeys;
        }

        private File getSource() {
            return source;
        }

        private String getClassName() {
            return className;
        }

        private Set<String> getTopLevelTypeKeys() {
            return topLevelTypeKeys;
        }
    }

    private static class CompileBatch {
        private final List<TestSourceUnit> sources = new ArrayList<>();
        private final Set<String> topLevelTypeKeys = new LinkedHashSet<>();

        private boolean canAdd(TestSourceUnit source) {
            for (String key : source.getTopLevelTypeKeys()) {
                if (topLevelTypeKeys.contains(key)) {
                    return false;
                }
            }
            return true;
        }

        private void add(TestSourceUnit source) {
            sources.add(source);
            topLevelTypeKeys.addAll(source.getTopLevelTypeKeys());
        }

        private List<TestSourceUnit> getSources() {
            return sources;
        }

        private List<File> getSourceFiles() {
            List<File> files = new ArrayList<>();
            for (TestSourceUnit source : sources) {
                files.add(source.getSource());
            }
            return files;
        }
    }

    private static class ProgressSnapshot {
        private final int total;
        private final int completed;
        private final String currentStep;
        private final String currentMethod;
        private final long startTimeMillis;
        private final Path outputPath;

        private ProgressSnapshot(int total, int completed, String currentStep, String currentMethod,
                                 long startTimeMillis, Path outputPath) {
            this.total = total;
            this.completed = completed;
            this.currentStep = currentStep;
            this.currentMethod = currentMethod;
            this.startTimeMillis = startTimeMillis;
            this.outputPath = outputPath;
        }

        private void restore(Config config) {
            if (config.getJobCount() != null) {
                config.getJobCount().set(total);
            }
            if (config.getCompletedJobCount() != null) {
                config.getCompletedJobCount().set(completed);
            }
            config.setCurrentStatusStep(currentStep);
            config.setCurrentStatusMethod(currentMethod);
            config.setStatusStartTimeMillis(startTimeMillis);
            config.setStatusOutputPath(outputPath);
        }
    }

    private static class InvalidTestSource {
        private String className;
        private String sourcePath;
        private String diagnostics;

        private String getClassName() {
            return className;
        }

        private void setClassName(String className) {
            this.className = className;
        }

        private String getSourcePath() {
            return sourcePath;
        }

        private void setSourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
        }

        private String getDiagnostics() {
            return diagnostics;
        }

        private void setDiagnostics(String diagnostics) {
            this.diagnostics = diagnostics;
        }
    }

    private static class CoverageResumeEntry {
        private String className;
        private String sourcePath;
        private String sourceIdentity;
        private String targetIdentity;
        private String status;
        private String executionDataFile;
        private String failureDiagnostics;
        private long testsFound;
        private long testsSucceeded;
        private long testsFailed;

        private String getClassName() {
            return className;
        }

        private void setClassName(String className) {
            this.className = className;
        }

        private void setSourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
        }

        private String getSourcePath() {
            return sourcePath;
        }

        private String getSourceIdentity() {
            return sourceIdentity;
        }

        private void setSourceIdentity(String sourceIdentity) {
            this.sourceIdentity = sourceIdentity;
        }

        private String getTargetIdentity() {
            return targetIdentity;
        }

        private void setTargetIdentity(String targetIdentity) {
            this.targetIdentity = targetIdentity;
        }

        private String getStatus() {
            return status;
        }

        private void setStatus(String status) {
            this.status = status;
        }

        private String getExecutionDataFile() {
            return executionDataFile;
        }

        private void setExecutionDataFile(String executionDataFile) {
            this.executionDataFile = executionDataFile;
        }

        private String getFailureDiagnostics() {
            return failureDiagnostics;
        }

        private void setFailureDiagnostics(String failureDiagnostics) {
            this.failureDiagnostics = failureDiagnostics;
        }

        private long getTestsFound() {
            return testsFound;
        }

        private void setTestsFound(long testsFound) {
            this.testsFound = testsFound;
        }

        private long getTestsSucceeded() {
            return testsSucceeded;
        }

        private void setTestsSucceeded(long testsSucceeded) {
            this.testsSucceeded = testsSucceeded;
        }

        private long getTestsFailed() {
            return testsFailed;
        }

        private void setTestsFailed(long testsFailed) {
            this.testsFailed = testsFailed;
        }
    }

    private static class TestCounts {
        private long testsFound;
        private long testsSucceeded;
        private long testsFailed;

        private void add(TestExecutionSummary summary) {
            testsFound += summary.getTestsFoundCount();
            testsSucceeded += summary.getTestsSucceededCount();
            testsFailed += summary.getTestsFailedCount();
        }

        private void add(long found, long succeeded, long failed) {
            testsFound += found;
            testsSucceeded += succeeded;
            testsFailed += failed;
        }

        private void addTimedOutTest() {
            testsFound += 1;
            testsFailed += 1;
        }

        private void addFailedTest() {
            testsFound += 1;
            testsFailed += 1;
        }

        private long getTestsFound() {
            return testsFound;
        }

        private long getTestsSucceeded() {
            return testsSucceeded;
        }

        private long getTestsFailed() {
            return testsFailed;
        }
    }
}
