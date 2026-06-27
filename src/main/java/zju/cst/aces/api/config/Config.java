package zju.cst.aces.api.config;

import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import zju.cst.aces.api.PreProcess;
import zju.cst.aces.api.Project;
import com.github.javaparser.JavaParser;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.Setter;
import okhttp3.OkHttpClient;
import zju.cst.aces.api.Validator;
import zju.cst.aces.api.impl.LoggerImpl;
import zju.cst.aces.api.impl.StatusOnlyLogger;
import zju.cst.aces.api.Logger;
import zju.cst.aces.api.impl.ValidatorImpl;
import zju.cst.aces.dto.OCM;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.parser.ProjectParser;
import zju.cst.aces.prompt.template.PromptTemplate;
import zju.cst.aces.progress.MethodProgressTracker;
import zju.cst.aces.scout.agent.ScoutAgent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Getter
@Setter
public class Config {
    public static final String NO_API = "NO_API";

    public String date;
    public Gson GSON;
    public Project project;
    public JavaParser parser;
    public PreProcess preProcessor;
    public JavaParserFacade parserFacade;
    public List<String> classPaths;
    public List<String> dependencyPaths;


    public Path promptPath;
    public Properties properties;
    public String url;
    public String apiKey;
    public String[] apiKeys;
    public Logger logger;
    public String OS;
    public boolean stopWhenSuccess;
    public boolean noExecution;
    public boolean enableMultithreading;
    public boolean enableRuleRepair;
    public boolean enableMerge;
    public boolean enableObfuscate;
    public boolean reportCoverage;
    public boolean statusOnlyOutput;
    public boolean coverageOnlyMode;
    public boolean resourceProfileEnabled;
    public String[] obfuscateGroupIds;
    public int maxThreads;
    public int classThreads;
    public int methodThreads;
    public int llmConcurrency;
    public int compileConcurrency;
    public int runConcurrency;
    public int coverageConcurrency;
    public int testNumber;
    public int maxRounds;
    public int maxPromptTokens;
    public int maxResponseTokens;
    public int minErrorTokens;
    public int sleepTime;
    public int dependencyDepth;
    public Model model;
    public String modelName;  // Direct model name (bypasses Model enum)
    public Double temperature;
    public int topP;
    public int frequencyPenalty;
    public int presencePenalty;
    public Path testOutput;
    public Path counterExamplePath;
    public Path tmpOutput;
    public Path compileOutputPath;
    public Path parseOutput;
    public Path errorOutput;
    public Path classNameMapPath;
    public Path historyPath;
    public Path examplePath;
    public Path symbolFramePath;
    public Path scoutRunOutput;
    public MethodProgressTracker progressTracker;
    public boolean resumeMode;

    public int max_coverage_improve_time;
    public int sampleSize;
    public String proxy;
    public String hostname;
    public String port;
    public OkHttpClient client;
    public AtomicInteger sharedInteger = new AtomicInteger(0);
    public AtomicInteger jobCount = new AtomicInteger(0);
    public AtomicInteger completedJobCount = new AtomicInteger(0);
    public AtomicInteger validUnitTestMethodCount = new AtomicInteger(0);
    public AtomicInteger compilationErrorCount = new AtomicInteger(0);
    public AtomicInteger runtimeErrorCount = new AtomicInteger(0);
    public AtomicInteger exportedTestCount = new AtomicInteger(0);
    public AtomicInteger validCoverageTestCount = new AtomicInteger(0);
    public AtomicInteger timeoutCount = new AtomicInteger(0);
    public AtomicInteger llmCallAttemptCount = new AtomicInteger(0);
    public AtomicInteger llmCallSuccessCount = new AtomicInteger(0);
    public AtomicInteger activeWorkerThreadCount = new AtomicInteger(0);
    public Map<String, TimingProfile> timingProfiles = new ConcurrentHashMap<>();
    private final Map<String, ResourceLimiter> resourceLimiters = new ConcurrentHashMap<>();
    public int fullyCoveredMethodCount;
    public Set<String> validUnitTestMethodKeys = ConcurrentHashMap.newKeySet();
    public volatile String currentStatusStep;
    public volatile String currentStatusMethod;
    public volatile long statusStartTimeMillis;
    public volatile Path statusOutputPath;
    public static Map<String, Map<String, String>> classMapping = new HashMap<>();
    public static Map<String, TreeSet<String>> objectConstructionCode = new HashMap<>();
    public static OCM ocm = new OCM();
    public Validator validator;
    public String pluginSign;
    public String phaseType;
    public boolean useSlice;
    public boolean useExtra;
    public ScoutAgent scoutAgent;

    public String cname;

    public void resetProgress(int totalMethodCount) {
        this.jobCount.set(Math.max(totalMethodCount, 0));
        this.completedJobCount.set(0);
        this.validUnitTestMethodCount.set(0);
        this.compilationErrorCount.set(0);
        this.runtimeErrorCount.set(0);
        this.exportedTestCount.set(0);
        this.validCoverageTestCount.set(0);
        this.timeoutCount.set(0);
        this.llmCallAttemptCount.set(0);
        this.llmCallSuccessCount.set(0);
        this.activeWorkerThreadCount.set(0);
        this.timingProfiles.clear();
        this.fullyCoveredMethodCount = 0;
        this.currentStatusStep = null;
        this.currentStatusMethod = null;
        this.statusStartTimeMillis = System.currentTimeMillis();
        this.statusOutputPath = this.testOutput;
        this.validUnitTestMethodKeys.clear();
    }

    public void resetStatusProgress(int totalJobCount, String statusStep, Path outputPath) {
        this.jobCount.set(Math.max(totalJobCount, 0));
        this.completedJobCount.set(0);
        this.currentStatusStep = statusStep;
        this.currentStatusMethod = null;
        this.activeWorkerThreadCount.set(0);
        this.statusStartTimeMillis = System.currentTimeMillis();
        this.statusOutputPath = outputPath == null ? this.testOutput : outputPath;
    }

    public int markMethodCompleted() {
        return this.completedJobCount.incrementAndGet();
    }

    public int markMethodWithValidTest(String fullClassName, MethodInfo methodInfo) {
        String methodSignature = methodInfo == null ? "" : safeProgressValue(methodInfo.getMethodSignature());
        String methodName = methodInfo == null ? "" : safeProgressValue(methodInfo.getMethodName());
        return markMethodWithValidTest(fullClassName, methodSignature, methodName);
    }

    public int markMethodWithValidTest(String fullClassName, String methodSignature, String methodName) {
        String key = methodProgressKey(fullClassName, methodSignature, methodName);
        if (this.validUnitTestMethodKeys.add(key)) {
            this.validUnitTestMethodCount.incrementAndGet();
        }
        return this.validUnitTestMethodCount.get();
    }

    public int markCompilationError() {
        return this.compilationErrorCount.incrementAndGet();
    }

    public int markRuntimeError() {
        return this.runtimeErrorCount.incrementAndGet();
    }

    public int markExportedTest() {
        return this.exportedTestCount.incrementAndGet();
    }

    public int markValidCoverageTest() {
        return this.validCoverageTestCount.incrementAndGet();
    }

    public int markTimeout() {
        return this.timeoutCount.incrementAndGet();
    }

    public int markLlmCallAttempt() {
        return this.llmCallAttemptCount.incrementAndGet();
    }

    public int markLlmCallSuccess() {
        return this.llmCallSuccessCount.incrementAndGet();
    }

    public int markWorkerThreadStarted() {
        return this.activeWorkerThreadCount.incrementAndGet();
    }

    public int markWorkerThreadFinished() {
        while (true) {
            int current = this.activeWorkerThreadCount.get();
            if (current <= 0) {
                return 0;
            }
            if (this.activeWorkerThreadCount.compareAndSet(current, current - 1)) {
                return current - 1;
            }
        }
    }

    public void rebalanceThreadAllocation(int classCount, int methodCount) {
        if (!this.enableMultithreading) {
            return;
        }
        int max = Math.max(1, this.maxThreads);
        int classes = Math.max(1, classCount);
        int methods = Math.max(1, methodCount);
        double averageMethodsPerClass = Math.max(1.0, methods * 1.0 / classes);
        int classThreadBudget = (int) Math.ceil(max / Math.sqrt(averageMethodsPerClass));
        int adjustedClassThreads = Math.max(1, Math.min(classes, Math.min(max, classThreadBudget)));
        int adjustedMethodThreads = Math.max(1, max / adjustedClassThreads);
        this.classThreads = adjustedClassThreads;
        this.methodThreads = adjustedMethodThreads;
    }

    public void recordTiming(String category, long elapsedNanos) {
        if (!this.resourceProfileEnabled) {
            return;
        }
        String key = safeTimingCategory(category);
        if (elapsedNanos < 0L) {
            elapsedNanos = 0L;
        }
        TimingProfile profile = this.timingProfiles.get(key);
        if (profile == null) {
            TimingProfile created = new TimingProfile(key);
            TimingProfile existing = this.timingProfiles.putIfAbsent(key, created);
            profile = existing == null ? created : existing;
        }
        profile.record(elapsedNanos);
    }

    public void recordTimingSince(String category, long startNanos) {
        recordTiming(category, System.nanoTime() - startNanos);
    }

    public String formatTimingProfile() {
        if (!this.resourceProfileEnabled) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Timing Profile\n")
                .append("--------------\n");
        if (this.timingProfiles.isEmpty()) {
            builder.append("No timing data recorded.\n");
            return builder.toString();
        }
        Map<String, TimingProfile> sorted = new TreeMap<>(this.timingProfiles);
        for (TimingProfile profile : sorted.values()) {
            long count = profile.getCount();
            long totalNanos = profile.getTotalNanos();
            long averageNanos = count == 0L ? 0L : totalNanos / count;
            builder.append(profile.getCategory())
                    .append(": count=").append(count)
                    .append(", total=").append(formatNanos(totalNanos))
                    .append(", avg=").append(formatNanos(averageNanos))
                    .append("\n");
        }
        return builder.toString();
    }

    public long averageTimingNanos(String category) {
        if (!this.resourceProfileEnabled) {
            return 0L;
        }
        TimingProfile profile = this.timingProfiles.get(safeTimingCategory(category));
        if (profile == null || profile.getCount() == 0L) {
            return 0L;
        }
        return profile.getTotalNanos() / profile.getCount();
    }

    public void initializeResourceLimiters() {
        this.resourceLimiters.clear();
        this.resourceLimiters.put("llm", new ResourceLimiter(normalizeResourceLimit(this.llmConcurrency)));
        this.resourceLimiters.put("compile", new ResourceLimiter(normalizeResourceLimit(this.compileConcurrency)));
        this.resourceLimiters.put("run", new ResourceLimiter(normalizeResourceLimit(this.runConcurrency)));
        this.resourceLimiters.put("coverage", new ResourceLimiter(normalizeResourceLimit(this.coverageConcurrency)));
    }

    public ResourceLease acquireResource(String category) {
        if (!this.resourceProfileEnabled) {
            return ResourceLease.noop();
        }
        ResourceLimiter limiter = limiterFor(category);
        limiter.acquire();
        return new ResourceLease(limiter);
    }

    public int getResourceLimit(String category) {
        if (!this.resourceProfileEnabled) {
            return 0;
        }
        return limiterFor(category).getLimit();
    }

    public int getActiveResourceCount(String category) {
        if (!this.resourceProfileEnabled) {
            return 0;
        }
        return limiterFor(category).getActiveCount();
    }

    public String formatResourceLimits() {
        return "llm " + resourceUsage("llm")
                + ", compile " + resourceUsage("compile")
                + ", run " + resourceUsage("run")
                + ", coverage " + resourceUsage("coverage");
    }

    private String resourceUsage(String category) {
        return getActiveResourceCount(category) + "/" + getResourceLimit(category);
    }

    private ResourceLimiter limiterFor(String category) {
        String key = safeResourceCategory(category);
        ResourceLimiter limiter = this.resourceLimiters.get(key);
        if (limiter == null) {
            ResourceLimiter created = new ResourceLimiter(1);
            ResourceLimiter existing = this.resourceLimiters.putIfAbsent(key, created);
            limiter = existing == null ? created : existing;
        }
        return limiter;
    }

    private String safeResourceCategory(String category) {
        String value = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? "unknown" : value;
    }

    private int normalizeResourceLimit(int limit) {
        return Math.max(1, limit);
    }

    public static class ResourceLease implements AutoCloseable {
        private final ResourceLimiter limiter;
        private boolean closed;

        private ResourceLease(ResourceLimiter limiter) {
            this.limiter = limiter;
        }

        private static ResourceLease noop() {
            return new ResourceLease(null);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (limiter != null) {
                    limiter.release();
                }
            }
        }
    }

    private static class ResourceLimiter {
        private final int limit;
        private final Semaphore semaphore;
        private final AtomicInteger activeCount = new AtomicInteger(0);

        ResourceLimiter(int limit) {
            this.limit = Math.max(1, limit);
            this.semaphore = new Semaphore(this.limit, true);
        }

        void acquire() {
            try {
                semaphore.acquire();
                activeCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for resource limiter.", e);
            }
        }

        void release() {
            while (true) {
                int current = activeCount.get();
                if (current <= 0) {
                    break;
                }
                if (activeCount.compareAndSet(current, current - 1)) {
                    break;
                }
            }
            semaphore.release();
        }

        int getLimit() {
            return limit;
        }

        int getActiveCount() {
            return activeCount.get();
        }
    }

    private String safeTimingCategory(String category) {
        String value = category == null ? "" : category.trim();
        return value.isEmpty() ? "unknown" : value;
    }

    private String formatNanos(long nanos) {
        long millis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(nanos));
        long hours = millis / 3_600_000L;
        long minutes = (millis % 3_600_000L) / 60_000L;
        long seconds = (millis % 60_000L) / 1_000L;
        long remainingMillis = millis % 1_000L;
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, remainingMillis);
    }

    @Getter
    public static class TimingProfile {
        private final String category;
        private final AtomicLong count = new AtomicLong(0L);
        private final AtomicLong totalNanos = new AtomicLong(0L);

        TimingProfile(String category) {
            this.category = category;
        }

        void record(long elapsedNanos) {
            this.count.incrementAndGet();
            this.totalNanos.addAndGet(elapsedNanos);
        }

        public long getCount() {
            return count.get();
        }

        public long getTotalNanos() {
            return totalNanos.get();
        }
    }

    public void updateCurrentStatusMethod(String fullClassName, MethodInfo methodInfo) {
        this.currentStatusMethod = methodProgressKey(fullClassName, methodInfo);
    }

    private String methodProgressKey(String fullClassName, MethodInfo methodInfo) {
        String methodSignature = methodInfo == null ? "" : safeProgressValue(methodInfo.getMethodSignature());
        String methodName = methodInfo == null ? "" : safeProgressValue(methodInfo.getMethodName());
        return methodProgressKey(fullClassName, methodSignature, methodName);
    }

    private String methodProgressKey(String fullClassName, String methodSignature, String methodName) {
        methodSignature = safeProgressValue(methodSignature);
        methodName = safeProgressValue(methodName);
        String className = safeProgressValue(fullClassName);
        if (methodSignature.length() > 0) {
            return className + "#" + methodSignature;
        }
        return className + "#" + methodName;
    }

    private String safeProgressValue(String value) {
        return value == null ? "" : value.trim();
    }

    public List<String> getTestClassPaths() {
        List<String> testClassPaths = new ArrayList<>();
        if (this.classPaths != null) {
            testClassPaths.addAll(this.classPaths);
        }
        if (this.dependencyPaths != null) {
            testClassPaths.addAll(this.dependencyPaths);
        }
        return testClassPaths;
    }

    @Getter
    @Setter
    public static class ConfigBuilder {
        private static final DateTimeFormatter SCOUT_RUN_DIRECTORY_FORMAT =
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
        public String date;
        public Project project;
        public JavaParser parser;
        public PreProcess preProcessor;
        public JavaParserFacade parserFacade;
        public List<String> classPaths;
        public List<String> dependencyPaths;

        public Path promptPath;
        public Properties properties;
        public String url;
        public String apiKey = NO_API;
        public String[] apiKeys;
        public Logger logger;
        public String OS = System.getProperty("os.name").toLowerCase();
        public boolean stopWhenSuccess = true;
        public boolean noExecution = false;
        public boolean enableMultithreading = true;
        public boolean enableRuleRepair = true;
        public boolean enableMerge = false;
        public boolean enableObfuscate = false;
        public boolean reportCoverage = true;
        public boolean statusOnlyOutput = false;
        public boolean coverageOnlyMode = false;
        public boolean resourceProfileEnabled = false;
        public String[] obfuscateGroupIds;
        public int maxThreads = Runtime.getRuntime().availableProcessors() * 5;
        public int classThreads = (int) Math.ceil((double)  this.maxThreads / 10);
        public int methodThreads = (int) Math.ceil((double) this.maxThreads / this.classThreads);
        public int llmConcurrency = 0;
        public int compileConcurrency = 0;
        public int runConcurrency = 0;
        public int coverageConcurrency = 0;
        public int testNumber = 5;
        public int maxRounds = 5;
        public int maxPromptTokens = 2600;
        public int maxResponseTokens = 1024;
        public int minErrorTokens = 500;
        public int sleepTime = 0;
        public int dependencyDepth = 1;
        public Model model = Model.GPT_3_5_TURBO;
        public String modelName;  // Direct model name (bypasses Model enum)
        public Double temperature = 0.5;
        public int topP = 1;
        public int frequencyPenalty = 0;
        public int presencePenalty = 0;
        public Path testOutput;
        public Path counterExamplePath;
        public Path tmpOutput = Paths.get(System.getProperty("java.io.tmpdir"), "chatunitest-info");
        public Path parseOutput;
        public Path compileOutputPath;
        public Path errorOutput;
        public Path classNameMapPath;
        public Path historyPath;
        public Path examplePath;
        public Path symbolFramePath;
        public Path scoutRunOutput;
        public boolean isolateTestOutputByRun = false;
        public boolean resumeMode = false;
        public String proxy = "null:-1";
        public String hostname = "null";
        public String port = "-1";
        public EnvironmentProxySelector.ProxyKind proxyKind = EnvironmentProxySelector.ProxyKind.HTTP;
        public boolean proxyConfigured = false;
        public boolean clientConfigured = false;
        public OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.MINUTES)
                .connectTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .build();
        public int max_coverage_improve_time=maxRounds;
        public int sampleSize = 10;
        public Validator validator;
        public String pluginSign;
        public String phaseType; //TODO
        public boolean useSlice;
        public boolean useExtra;
        public ScoutAgent scoutAgent;

        public String cname = "";

        public ConfigBuilder(Project project) {
            initDefault(project);
        }

        public void initDefault(Project project) {
            this.date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")).toString();
            this.project = project;
            assert(project.getClassPaths() != null);
            this.classPaths = project.getClassPaths();

            this.dependencyPaths = project.getDependencyPaths();

            this.logger = new LoggerImpl();
            this.parser = new JavaParser();
            JavaSymbolSolver symbolSolver = getSymbolSolver();
            parser.getParserConfiguration().setSymbolResolver(symbolSolver);
            ProjectParser.setLanguageLevel(parser.getParserConfiguration());

            this.properties("config.properties");

            this.maxPromptTokens = this.model.getDefaultConfig().getContextLength() * 2 / 3;
            this.maxResponseTokens = 1024;
            this.minErrorTokens = this.maxPromptTokens * 1 / 3 - this.maxResponseTokens;
            if (this.minErrorTokens < 0) {
                this.minErrorTokens = 512;
            }

            Project parent = project.getParent();
            while(parent != null && parent.getBasedir() != null) {
                this.tmpOutput = this.tmpOutput.resolve(parent.getArtifactId());
                parent = parent.getParent();
            }
            this.tmpOutput = this.tmpOutput.resolve(project.getArtifactId());
            configureTmpDerivedPaths();

            
            this.counterExamplePath = project.getBasedir().toPath().resolve("smartut-tests");
            this.testOutput = project.getBasedir().toPath().resolve("chatunitest-tests");

            configureValidator();
        }

        public ConfigBuilder maxThreads(int maxThreads) {
            if (maxThreads <= 0) {
                this.maxThreads = Runtime.getRuntime().availableProcessors() * 5;
            } else {
                this.maxThreads = maxThreads;
            }
            this.classThreads = (int) Math.ceil((double)  this.maxThreads / 10);
            this.methodThreads = (int) Math.ceil((double) this.maxThreads / this.classThreads);
            if (this.stopWhenSuccess == false) {
                this.methodThreads = (int) Math.ceil((double)  this.methodThreads / this.testNumber);
            }
            return this;
        }

        public ConfigBuilder proxy(String proxy) {
            this.proxyConfigured = true;
            setProxy(proxy);
            return this;
        }
        public ConfigBuilder max_coverage_improve_time(int max_coverage_improve_time){
            this.max_coverage_improve_time=max_coverage_improve_time;
            return this;
        }

        public ConfigBuilder sampleSize(int sampleSize) {
            this.sampleSize = sampleSize;
            return this;
        }

        public ConfigBuilder cname(String cname) {
            this.cname = cname == null ? "" : cname.trim();
            return this;
        }


        public ConfigBuilder tmpOutput(Path tmpOutput) {
            this.tmpOutput = tmpOutput;
            Project parent = project.getParent();
            while(parent != null && parent.getBasedir() != null) {
                this.tmpOutput = this.tmpOutput.resolve(parent.getArtifactId());
                parent = parent.getParent();
            }
            this.tmpOutput = this.tmpOutput.resolve(project.getArtifactId());
            this.scoutRunOutput = null;
            configureTmpDerivedPaths();
            configureValidator();
            return this;
        }
        public ConfigBuilder CounterExamplePath(Path counterExamplePath) {
            if (counterExamplePath == null) {
                this.counterExamplePath = project.getBasedir().toPath().resolve("smartut-tests");
            } else {
                this.counterExamplePath = counterExamplePath;
                Project parent = project.getParent();
                while(parent != null && parent.getBasedir() != null) {
                    this.counterExamplePath = this.counterExamplePath.resolve(parent.getArtifactId());
                    parent = parent.getParent();
                }
                this.counterExamplePath = this.counterExamplePath.resolve(project.getArtifactId());
            }
            return this;
        }
        public ConfigBuilder project(Project project) {
            this.project = project;
            return this;
        }

        public ConfigBuilder pluginSign(String pluginSign){
            this.pluginSign=pluginSign;
            return this;
        }

        public ConfigBuilder phaseType(String phaseType){
            this.phaseType=phaseType;
            return this;
        }

        public ConfigBuilder useSlice(boolean useSlice){
            this.useSlice=useSlice;
            return this;
        }

        public ConfigBuilder useExtra(boolean useExtra){
            this.useExtra=useExtra;
            return this;
        }

        public ConfigBuilder scoutAgent(ScoutAgent scoutAgent) {
            this.scoutAgent = scoutAgent;
            return this;
        }

        public ConfigBuilder promptPath(File promptPath) {
            if (promptPath != null) {
                this.promptPath = promptPath.toPath();
            }
            return this;
        }

        public ConfigBuilder parser(JavaParser parser) {
            this.parser = parser;
            return this;
        }

        public ConfigBuilder preProcessor(PreProcess preProcessor) {
            this.preProcessor = preProcessor;
            return this;
        }

        public ConfigBuilder parserFacade(JavaParserFacade parserFacade) {
            this.parserFacade = parserFacade;
            return this;
        }

        public ConfigBuilder classPaths(List<String> classPaths) {
            this.classPaths = classPaths;
            this.validator = new ValidatorImpl(this.testOutput, this.compileOutputPath,
                    this.project.getBasedir().toPath().resolve("target"), this.classPaths, this.dependencyPaths);
            return this;
        }

        public ConfigBuilder dependencyPaths(List<String> dependencyPaths) {
            this.dependencyPaths = dependencyPaths;
            this.validator = new ValidatorImpl(this.testOutput, this.compileOutputPath,
                    this.project.getBasedir().toPath().resolve("target"), this.classPaths, this.dependencyPaths);
            return this;
        }

        public ConfigBuilder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        public ConfigBuilder OS(String OS) {
            this.OS = OS;
            return this;
        }

        public ConfigBuilder stopWhenSuccess(boolean stopWhenSuccess) {
            this.stopWhenSuccess = stopWhenSuccess;
            return this;
        }

        public ConfigBuilder noExecution(boolean noExecution) {
            this.noExecution = noExecution;
            return this;
        }

        public ConfigBuilder enableMultithreading(boolean enableMultithreading) {
            this.enableMultithreading = enableMultithreading;
            return this;
        }

        public ConfigBuilder enableRuleRepair(boolean enableRuleRepair) {
            this.enableRuleRepair = enableRuleRepair;
            return this;
        }

        public ConfigBuilder enableMerge(boolean enableMerge) {
            this.enableMerge = enableMerge;
            return this;
        }

        public ConfigBuilder enableObfuscate(boolean enableObfuscate) {
            this.enableObfuscate = enableObfuscate;
            return this;
        }

        public ConfigBuilder reportCoverage(boolean reportCoverage) {
            this.reportCoverage = reportCoverage;
            return this;
        }

        public ConfigBuilder statusOnlyOutput(boolean statusOnlyOutput) {
            this.statusOnlyOutput = statusOnlyOutput;
            if (statusOnlyOutput) {
                this.logger = new StatusOnlyLogger();
            }
            return this;
        }

        public ConfigBuilder coverageOnlyMode(boolean coverageOnlyMode) {
            this.coverageOnlyMode = coverageOnlyMode;
            return this;
        }

        public ConfigBuilder resourceProfileEnabled(boolean resourceProfileEnabled) {
            this.resourceProfileEnabled = resourceProfileEnabled;
            return this;
        }

        public ConfigBuilder properties(String configFile) {
            try {
                Properties properties = new Properties();
                InputStream inputStream = PromptTemplate.class.getClassLoader().getResourceAsStream(configFile);
                properties.load(inputStream);
                this.properties = properties;
                return this;
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("Failed to load properties file: " + configFile);
            }
        }

        public ConfigBuilder obfuscateGroupIds(String[] obfuscateGroupIds) {
            this.obfuscateGroupIds = obfuscateGroupIds;
            return this;
        }

        public ConfigBuilder classThreads(int classThreads) {
            this.classThreads = classThreads;
            return this;
        }

        public ConfigBuilder methodThreads(int methodThreads) {
            this.methodThreads = methodThreads;
            return this;
        }

        public ConfigBuilder llmConcurrency(int llmConcurrency) {
            this.llmConcurrency = llmConcurrency;
            return this;
        }

        public ConfigBuilder compileConcurrency(int compileConcurrency) {
            this.compileConcurrency = compileConcurrency;
            return this;
        }

        public ConfigBuilder runConcurrency(int runConcurrency) {
            this.runConcurrency = runConcurrency;
            return this;
        }

        public ConfigBuilder coverageConcurrency(int coverageConcurrency) {
            this.coverageConcurrency = coverageConcurrency;
            return this;
        }

        public ConfigBuilder url(String url) {
            this.url = url;
            if (this.model != null) {
                this.model.getDefaultConfig().setUrl(url);
            }
            return this;
        }

        public ConfigBuilder apiKeys(String[] apiKeys) {
            this.apiKeys = apiKeys;
            return this;
        }

        public ConfigBuilder apiKey(String apiKey) {
            this.apiKey = normalizeApiKey(apiKey);
            if (this.model != null && this.model.getDefaultConfig() != null) {
                this.model.getDefaultConfig().setApiKey(this.apiKey);
            }
            return this;
        }

        public ConfigBuilder testNumber(int testNumber) {
            this.testNumber = testNumber;
            return this;
        }

        public ConfigBuilder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public ConfigBuilder maxPromptTokens(int maxPromptTokens) {
            if (maxPromptTokens > 0) {
                this.maxPromptTokens = maxPromptTokens;
            }
            return this;
        }

        public ConfigBuilder maxResponseTokens(int maxResponseTokens) {
            this.maxResponseTokens = maxResponseTokens;
            return this;
        }

        public ConfigBuilder minErrorTokens(int minErrorTokens) {
            this.minErrorTokens = minErrorTokens;
            return this;
        }

        public ConfigBuilder sleepTime(int sleepTime) {
            this.sleepTime = sleepTime;
            return this;
        }

        public ConfigBuilder dependencyDepth(int dependencyDepth) {
            this.dependencyDepth = dependencyDepth;
            return this;
        }

        public ConfigBuilder model(String model) {
            this.modelName = model;  // Store model name directly
            try {
                this.model = Model.fromString(model);
            } catch (IllegalArgumentException e) {
                this.model = null;  // Allow unknown models
            }
            this.maxPromptTokens = 131072 * 2 / 3;  // Default context length
            this.maxResponseTokens = 1024;
            this.minErrorTokens = this.maxPromptTokens * 1 / 2 - this.maxResponseTokens;
            if (this.minErrorTokens < 0) {
                this.minErrorTokens = 512;
            }
            return this;
        }

        public ConfigBuilder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public ConfigBuilder topP(int topP) {
            this.topP = topP;
            return this;
        }

        public ConfigBuilder frequencyPenalty(int frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public ConfigBuilder presencePenalty(int presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        public ConfigBuilder testOutput(Path testOutput) {
            if (testOutput == null) {
                this.testOutput = project.getBasedir().toPath().resolve("chatunitest-tests");
            } else {
                this.testOutput = testOutput;
                // Project parent = project.getParent();
                // while(parent != null && parent.getBasedir() != null) {
                //     this.testOutput = this.testOutput.resolve(parent.getArtifactId());
                //     parent = parent.getParent();
                // }
                // this.testOutput = this.testOutput.resolve(project.getArtifactId());
            }
            return this;
        }

        public ConfigBuilder isolateTestOutputByRun(boolean isolateTestOutputByRun) {
            this.isolateTestOutputByRun = isolateTestOutputByRun;
            return this;
        }

        public ConfigBuilder resumeMode(boolean resumeMode) {
            this.resumeMode = resumeMode;
            return this;
        }

        public ConfigBuilder compileOutputPath(Path compileOutputPath) {
            this.compileOutputPath = compileOutputPath;
            return this;
        }

        public ConfigBuilder parseOutput(Path parseOutput) {
            this.parseOutput = parseOutput;
            return this;
        }

        public ConfigBuilder errorOutput(Path errorOutput) {
            this.errorOutput = errorOutput;
            return this;
        }

        public ConfigBuilder classNameMapPath(Path classNameMapPath) {
            this.classNameMapPath = classNameMapPath;
            return this;
        }

        public ConfigBuilder examplePath(Path examplePath) {
            this.examplePath = examplePath;
            return this;
        }

        public ConfigBuilder symbolFramePath(Path symbolFramePath) {
            this.symbolFramePath = symbolFramePath;
            return this;
        }

        public ConfigBuilder hostname(String hostname) {
            this.hostname = hostname;
            return this;
        }

        public ConfigBuilder port(String port) {
            this.port = port;
            return this;
        }

        public ConfigBuilder client(OkHttpClient client) {
            this.clientConfigured = true;
            this.client = client;
            return this;
        }

        public void setProxy(String proxy) {
            this.proxy = proxy;
            Optional<EnvironmentProxySelector.ProxySettings> settings =
                    EnvironmentProxySelector.parseProxyValue(proxy, EnvironmentProxySelector.ProxyKind.HTTP);
            if (settings.isPresent()) {
                setProxy(settings.get());
            } else {
                this.hostname = "null";
                this.port = "-1";
                this.proxyKind = EnvironmentProxySelector.ProxyKind.HTTP;
                setClient();
            }
        }

        public void setProxyStr() {
            this.hostname = this.proxy.split(":")[0];
            this.port = this.proxy.split(":")[1];
        }

        public void setClient() {
            this.client = new OkHttpClient.Builder()
                    .callTimeout(3, TimeUnit.MINUTES)
                    .connectTimeout(5, TimeUnit.MINUTES)
                    .writeTimeout(5, TimeUnit.MINUTES)
                    .readTimeout(5, TimeUnit.MINUTES)
                    .build();
        }

        public void setClientwithProxy() {
            setClientwithProxy(EnvironmentProxySelector.ProxyKind.HTTP);
        }

        public void setClientwithProxy(EnvironmentProxySelector.ProxyKind proxyKind) {
            Proxy.Type proxyType = proxyKind == EnvironmentProxySelector.ProxyKind.SOCKS
                    ? Proxy.Type.SOCKS
                    : Proxy.Type.HTTP;
            Proxy proxy = new Proxy(proxyType, new InetSocketAddress(this.hostname, Integer.parseInt(this.port)));
            this.client = new OkHttpClient.Builder()
                    .callTimeout(3, TimeUnit.MINUTES)
                    .connectTimeout(5, TimeUnit.MINUTES)
                    .writeTimeout(5, TimeUnit.MINUTES)
                    .readTimeout(5, TimeUnit.MINUTES)
                    .proxy(proxy)
                    .build();
        }

        private void setProxy(EnvironmentProxySelector.ProxySettings settings) {
            this.hostname = settings.getHost();
            this.port = String.valueOf(settings.getPort());
            this.proxyKind = settings.getKind();
            this.proxy = this.hostname + ":" + this.port;
            setClientwithProxy(this.proxyKind);
        }

        private void applyEnvironmentProxyIfUnset() {
            if (this.proxyConfigured || this.clientConfigured) {
                return;
            }

            Optional<EnvironmentProxySelector.ProxySettings> settings =
                    EnvironmentProxySelector.select(resolveProxyTargetUrl(), System.getenv());
            settings.ifPresent(this::setProxy);
        }

        private String resolveProxyTargetUrl() {
            if (this.url != null && !this.url.trim().isEmpty()) {
                return this.url;
            }
            if (this.model != null && this.model.getDefaultConfig() != null) {
                return this.model.getDefaultConfig().getUrl();
            }
            return null;
        }

        public void setValidator(Validator validator) {
            this.validator = validator;
        }

        public JavaSymbolSolver getSymbolSolver() {
            CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
            combinedTypeSolver.add(new ReflectionTypeSolver());
            for (String dep : this.getClassPaths()) {
                try {
                    File depFile = new File(dep);
                    if (!depFile.exists() || !dep.endsWith("jar")) {
                        continue;
                    }
                    combinedTypeSolver.add(new JarTypeSolver(depFile));
                } catch (Exception e) {
                    this.getLogger().warn(e.getMessage());
                    this.getLogger().debug(e.getMessage());
                }
            }

            for (String src : this.getProject().getCompileSourceRoots()) { // TODO: remove MavenProject
                if (new File(src).exists()) {
                    combinedTypeSolver.add(new JavaParserTypeSolver(src));
                }
            }
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
            this.setParserFacade(JavaParserFacade.get(combinedTypeSolver));
            return symbolSolver;
        }

        public Config build() {
            applyEnvironmentProxyIfUnset();
            applyScoutRunOutputIfNeeded();
            applyTestRunOutputIfNeeded();

            Config config = new Config();
            config.setDate(this.date);
            config.setGSON(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create());
            config.setProject(this.project);
            config.setParser(this.parser);
            config.setPreProcessor(this.preProcessor);
            config.setParserFacade(this.parserFacade);
            config.setClassPaths(this.classPaths);
            config.setDependencyPaths(this.dependencyPaths);

            config.setPromptPath(this.promptPath);
            config.setProperties(this.properties);
            config.setUrl(this.url);
            String resolvedApiKey = normalizeApiKey(this.apiKey);
            String[] resolvedApiKeys = normalizeApiKeys(resolvedApiKey, this.apiKeys);
            if (NO_API.equals(resolvedApiKey) && resolvedApiKeys.length > 0) {
                resolvedApiKey = normalizeApiKey(resolvedApiKeys[0]);
            }
            config.setApiKey(resolvedApiKey);
            config.setApiKeys(resolvedApiKeys);
            config.setOS(this.OS);
            config.setStopWhenSuccess(this.stopWhenSuccess);
            config.setNoExecution(this.noExecution);
            config.setEnableMultithreading(this.enableMultithreading);
            config.setEnableRuleRepair(this.enableRuleRepair);
            config.setEnableMerge(this.enableMerge);
            config.setEnableObfuscate(this.enableObfuscate);
            config.setReportCoverage(this.reportCoverage);
            config.setStatusOnlyOutput(this.statusOnlyOutput);
            config.setCoverageOnlyMode(this.coverageOnlyMode);
            config.setResourceProfileEnabled(this.resourceProfileEnabled);
            config.setObfuscateGroupIds(this.obfuscateGroupIds);
            config.setMaxThreads(this.maxThreads);
            config.setClassThreads(this.classThreads);
            config.setMethodThreads(this.methodThreads);
            config.setLlmConcurrency(resolveLlmConcurrency());
            config.setCompileConcurrency(resolveCpuConcurrency(this.compileConcurrency));
            config.setRunConcurrency(resolveCpuConcurrency(this.runConcurrency));
            config.setCoverageConcurrency(resolveCpuConcurrency(this.coverageConcurrency));
            config.setTestNumber(this.testNumber);
            config.setMaxRounds(this.maxRounds);
            config.setMaxPromptTokens(this.maxPromptTokens);
            config.setMaxResponseTokens(this.maxResponseTokens);
            config.setMinErrorTokens(this.minErrorTokens);
            config.setSleepTime(this.sleepTime);
            config.setDependencyDepth(this.dependencyDepth);
            config.setModel(this.model);
            config.setModelName(this.modelName);
            config.setTemperature(this.temperature);
            config.setTopP(this.topP);
            config.setFrequencyPenalty(this.frequencyPenalty);
            config.setPresencePenalty(this.presencePenalty);
            config.setTestOutput(this.testOutput);
            config.setCounterExamplePath(this.counterExamplePath);
            config.setTmpOutput(this.tmpOutput);
            config.setCompileOutputPath(this.compileOutputPath);
            config.setParseOutput(this.parseOutput);
            config.setErrorOutput(this.errorOutput);
            config.setClassNameMapPath(this.classNameMapPath);
            config.setHistoryPath(this.historyPath);
            config.setExamplePath(this.examplePath);
            config.setSymbolFramePath(this.symbolFramePath);
            config.setScoutRunOutput(this.scoutRunOutput);
            config.setProxy(this.proxy);
            config.setHostname(this.hostname);
            config.setPort(this.port);
            config.setClient(this.client);
            config.setLogger(this.logger);
            config.setValidator(this.validator);
            config.setPluginSign(this.pluginSign);
            config.setPhaseType(this.phaseType);

            config.setUseSlice(this.useSlice);
            config.setUseExtra(this.useExtra);
            config.setScoutAgent(this.scoutAgent);
            config.setMax_coverage_improve_time(this.max_coverage_improve_time);
            config.setSampleSize(this.sampleSize);

            config.setCname(this.cname);
            config.setResumeMode(this.resumeMode);
            if (this.testOutput != null) {
                config.setProgressTracker(
                        MethodProgressTracker.forRunDirectory(this.testOutput, this.logger));
            }
            config.initializeResourceLimiters();
            return config;
        }

        private int resolveLlmConcurrency() {
            if (this.llmConcurrency > 0) {
                return this.llmConcurrency;
            }
            return Math.max(1, Math.min(4, normalizedMaxThreads()));
        }

        private int resolveCpuConcurrency(int configured) {
            if (configured > 0) {
                return configured;
            }
            int cpuHalf = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            return Math.max(1, Math.min(normalizedMaxThreads(), cpuHalf));
        }

        private int normalizedMaxThreads() {
            return Math.max(1, this.maxThreads);
        }

        private String normalizeApiKey(String apiKey) {
            return apiKey == null || apiKey.trim().isEmpty() ? NO_API : apiKey.trim();
        }

        private String[] normalizeApiKeys(String apiKey, String[] apiKeys) {
            ArrayList<String> normalized = new ArrayList<>();
            if (apiKeys != null) {
                for (String key : apiKeys) {
                    String normalizedKey = normalizeApiKey(key);
                    if (!NO_API.equals(normalizedKey)) {
                        normalized.add(normalizedKey);
                    }
                }
            }
            if (normalized.isEmpty()) {
                normalized.add(normalizeApiKey(apiKey));
            }
            return normalized.toArray(new String[0]);
        }

        private void applyScoutRunOutputIfNeeded() {
            if (this.phaseType == null || !"SCOUT".equalsIgnoreCase(this.phaseType)) {
                return;
            }
            if (this.scoutRunOutput == null) {
                this.scoutRunOutput = createScoutRunOutput(this.tmpOutput);
            }
            this.tmpOutput = this.scoutRunOutput;
            configureTmpDerivedPaths();
            configureValidator();
        }

        private void applyTestRunOutputIfNeeded() {
            if (!this.isolateTestOutputByRun) {
                return;
            }
            if (this.testOutput == null) {
                this.testOutput = project.getBasedir().toPath().resolve("chatunitest-tests");
            }
            this.testOutput = createTimestampedRunDirectory(this.testOutput.resolve(resolveRunModelDirectoryName()),
                    "test output run directory");
            configureValidator();
        }

        private Path createScoutRunOutput(Path baseTmpOutput) {
            return createTimestampedRunDirectory(baseTmpOutput.resolve("scout-runs").resolve(resolveRunModelDirectoryName()),
                    "SCOUT run directory");
        }

        private String resolveRunModelDirectoryName() {
            String rawModelName = this.modelName;
            if ((rawModelName == null || rawModelName.trim().isEmpty()) && this.model != null) {
                rawModelName = this.model.getModelName();
            }
            return sanitizeRunDirectorySegment(rawModelName, "unknown-model");
        }

        private String sanitizeRunDirectorySegment(String value, String fallback) {
            if (value == null) {
                return fallback;
            }
            String sanitized = value.trim()
                    .replaceAll("[^A-Za-z0-9._-]+", "-")
                    .replaceAll("^-+|-+$", "");
            return sanitized.isEmpty() ? fallback : sanitized;
        }

        private Path createTimestampedRunDirectory(Path runsRoot, String description) {
            String timestamp = LocalDateTime.now().format(SCOUT_RUN_DIRECTORY_FORMAT);
            for (int i = 0; i < 1000; i++) {
                Path candidate = runsRoot.resolve(i == 0 ? timestamp : timestamp + "-" + i);
                try {
                    Files.createDirectories(runsRoot);
                    return Files.createDirectory(candidate);
                } catch (IOException e) {
                    if (Files.exists(candidate)) {
                        continue;
                    }
                    throw new IllegalStateException("Failed to create " + description + ": " + candidate, e);
                }
            }
            throw new IllegalStateException("Failed to create unique " + description + " under " + runsRoot);
        }

        private void configureTmpDerivedPaths() {
            this.compileOutputPath = this.tmpOutput.resolve("build");
            this.parseOutput = this.tmpOutput.resolve("class-info");
            this.errorOutput = this.tmpOutput.resolve("error-message");
            this.classNameMapPath = this.tmpOutput.resolve("classNameMapping.json");
            this.historyPath = this.tmpOutput.resolve("history" + this.date);
            this.symbolFramePath = this.tmpOutput.resolve("symbolFrames.json");
        }

        private void configureValidator() {
            this.validator = new ValidatorImpl(this.testOutput, this.compileOutputPath,
                    this.project.getBasedir().toPath().resolve("target"), this.classPaths, this.dependencyPaths);
        }
    }

    public String getRandomKey() {
        Random rand = new Random();
        if (apiKeys == null || apiKeys.length == 0) {
            return NO_API;
        }
        String apiKey = apiKeys[rand.nextInt(apiKeys.length)];
        return apiKey == null || apiKey.trim().isEmpty() ? NO_API : apiKey.trim();
    }

    public void print() {
        logger.info("\n========================== Configuration ==========================\n");
        logger.info("PluginSign >>>>"+this.getPluginSign() );
        logger.info(" Multithreading >>>> " + this.isEnableMultithreading());
        if (this.isEnableMultithreading()) {
            logger.info(" - Class threads: " + this.getClassThreads() + ", Method threads: " + this.getMethodThreads());
        }
        logger.info(" Stop when success >>>> " + this.isStopWhenSuccess());
        logger.info(" No execution >>>> " + this.isNoExecution());
        logger.info(" Enable Merge >>>> " + this.isEnableMerge());
        logger.info(" Report Coverage >>>> " + this.isReportCoverage());
        logger.info(" --- ");
        logger.info(" TestOutput Path >>> " + this.getTestOutput());
        logger.info(" TmpOutput Path >>> " + this.getTmpOutput());
        logger.info(" CounterExample Path >>> " + this.getCounterExamplePath());
        logger.info(" Prompt path >>> " + this.getPromptPath());
        logger.info(" Example path >>> " + this.getExamplePath());
        logger.info(" --- ");
        logger.info(" Model >>> " + (this.getModel() != null ? this.getModel() : this.getModelName()));
        logger.info(" Url >>> " + this.getUrl());
        logger.info(" MaxPromptTokens >>> " + this.getMaxPromptTokens());
        logger.info(" MaxResponseTokens >>> " + this.getMaxResponseTokens());
        logger.info(" MinErrorTokens >>> " + this.getMinErrorTokens());
        logger.info(" MaxThreads >>> " + this.getMaxThreads());
        logger.info(" TestNumber >>> " + this.getTestNumber());
        logger.info(" MaxRounds >>> " + this.getMaxRounds());
        logger.info(" SleepTime >>> " + this.getSleepTime());
        logger.info(" DependencyDepth >>> " + this.getDependencyDepth());
        logger.info(" SampleSize >>> " + this.getSampleSize());
        logger.info(" PhaseType >>> " + this.phaseType);
        logger.info(" TargetClass >>> " + this.cname);
        logger.info("\n===================================================================\n");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public void setSampleSize(int sampleSize) {
        this.sampleSize = sampleSize;
    }
}
