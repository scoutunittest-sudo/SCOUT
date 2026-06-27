package zju.cst.aces.api;

import zju.cst.aces.api.phase.Phase;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.coverage.CoverageSummaryReporter;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.parser.ProjectParser;
import zju.cst.aces.runner.AbstractRunner;
import zju.cst.aces.scout.description.ScoutDescriptionPreloader;
import zju.cst.aces.scout.state.ScoutStateStore;
import zju.cst.aces.status.StatusWindow;
import zju.cst.aces.util.ClassNameProcessor;
import zju.cst.aces.util.TestClassMerger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import zju.cst.aces.util.Counter;

public class Task {

    Config config;
    Logger log;
    Runner runner;
    private final StatusWindow statusWindow = new StatusWindow();
    public enum Granularity {
        METHOD,
        CLASS,
        PROJECT;
    }
    Granularity granularity;
    ClassNameProcessor classNameProcessor;

    public Task(Config config, Runner runner) {
        this.config = config;
        this.log = config.getLogger();
        this.runner = runner;
        this.classNameProcessor=new ClassNameProcessor();
    }

    public Config getConfig() {
        return config;
    }

    public void startMethodTask(String className, String methodName) throws IOException {
        if(granularity == null){
            granularity = Granularity.METHOD;
        }
        try {
            checkTargetFolder(config.getProject());
        } catch (RuntimeException e) {
            log.error(e.toString());
            return;
        }
        if (config.getProject().getPackaging().equals("pom")) {
            log.info(String.format("\n==========================\n[%s] Skip pom-packaging ...",config.pluginSign));
            return;
        }

        Phase phase = PhaseImpl.createPhase(config);
        phase.prepare();

            log.info(String.format("\n==========================\n[%s] Generating tests for class: < ",config.pluginSign) + className
                + "> method: < " + methodName + " > ...");

        try {
            String fullClassName = getFullClassName(config, className);
            ClassInfo classInfo = AbstractRunner.getClassInfo(config, fullClassName);
            int targetMethodCount = countTargetMethods(classInfo, methodName);
            config.resetProgress(targetMethodCount);
            printStatusWindow();
            MethodInfo methodInfo = null;
            if (methodName.matches("\\d+")) { // use method id instead of method name
                String methodId = methodName;
                for (String mSig : classInfo.methodSigs.keySet()) {
                    if (classInfo.methodSigs.get(mSig).equals(methodId)) {
                        methodInfo = AbstractRunner.getMethodInfo(config, classInfo, mSig);
                        break;
                    }
                }
                if (methodInfo == null) {
                    throw new IOException("Method " + methodName + " in class " + fullClassName + " not found");
                }
                try {
                    runMethodWithProgress(fullClassName, methodInfo);
                } catch (Exception e) {
                    log.error("Error when generating tests for " + methodName + " in " + className + " " + config.getProject().getArtifactId() + "\n" + e.getMessage());
                }
            } else {
                for (String mSig : classInfo.methodSigs.keySet()) {
                    if (mSig.split("\\(")[0].equals(methodName)) {
                        methodInfo = AbstractRunner.getMethodInfo(config, classInfo, mSig);
                        if (methodInfo == null) {
                            throw new IOException("Method " + methodName + " in class " + fullClassName + " not found");
                        }
                        try {
                            runMethodWithProgress(fullClassName, methodInfo);
                        } catch (Exception e) {
                            log.error("Error when generating tests for " + methodName + " in " + className + " " + config.getProject().getArtifactId() + "\n" + e.getMessage());
                        }
                    }
                }
            }

        } catch (IOException e) {
            log.warn("Method not found: " + methodName + " in " + className + " " + config.getProject().getArtifactId());
            return;
        }

        log.info(String.format("\n==========================\n[%s] Generation finished", config.pluginSign));

        Path testOutPutPath = config.getTestOutput();
        classNameProcessor.processJavaFiles(testOutPutPath);
        log.info(String.format("\n==========================\n[%s] Test processed", config.pluginSign));
        reportCoverageIfEnabled();
    }

    /**
     * Start a method task with specific method signature to distinguish between overloaded methods.
     * This method will only execute the method that exactly matches the provided method signature.
     *
     * @param className The name of the class
     * @param methodName The name of the method
     * @param paramTypes String representing the full method signature to match with methodInfo.methodSignature
     * @throws IOException If an I/O error occurs
     */
    public void startMethodWithoutOverloadTask(String className, String methodName, String paramTypes) throws IOException {
        if(granularity == null){
            granularity = Granularity.METHOD;
        }
        try {
            checkTargetFolder(config.getProject());
        } catch (RuntimeException e) {
            log.error(e.toString());
            return;
        }
        if (config.getProject().getPackaging().equals("pom")) {
            log.info(String.format("\n==========================\n[%s] Skip pom-packaging ...",config.pluginSign));
            return;
        }

        Phase phase = PhaseImpl.createPhase(config);
        phase.prepare();

            log.info(String.format("\n==========================\n[%s] Generating tests for class: < ",config.pluginSign) + className
                + "> method with signature: < " + paramTypes + " > ...");

        try {
            String fullClassName = getFullClassName(config, className);
            ClassInfo classInfo = AbstractRunner.getClassInfo(config, fullClassName);
            config.resetProgress(1);
            printStatusWindow();
            MethodInfo methodInfo = null;
            if (methodName.matches("\\d+")) { // use method id instead of method name
                String methodId = methodName;
                for (String mSig : classInfo.methodSigs.keySet()) {
                    if (classInfo.methodSigs.get(mSig).equals(methodId)) {
                        methodInfo = AbstractRunner.getMethodInfo(config, classInfo, mSig);
                        break;
                    }
                }
                if (methodInfo == null) {
                    throw new IOException("Method " + methodName + " in class " + fullClassName + " not found");
                }
                try {
                    runMethodWithProgress(fullClassName, methodInfo);
                } catch (Exception e) {
                    log.error("Error when generating tests for " + methodName + " in " + className + " " + config.getProject().getArtifactId() + "\n" + e.getMessage());
                }
            } else {
                boolean methodFound = false;
                for (String mSig : classInfo.methodSigs.keySet()) {
                    // Check if method name matches
                    if (mSig.split("\\(")[0].equals(methodName)) {
                        // Get the method info to check parameters
                        MethodInfo tempMethodInfo = AbstractRunner.getMethodInfo(config, classInfo, mSig);
                        if (tempMethodInfo == null) {
                            continue;
                        }

                        // Check if the method signature matches the provided parameter string
                        // The methodSignature attribute contains the full method signature including name and parameters
                        String methodSignature = tempMethodInfo.methodSignature;

                        // Directly compare the method signature with the provided parameter string
                        if (methodSignature.equals(paramTypes)) {
                            methodInfo = tempMethodInfo;
                            methodFound = true;
                            try {
                                runMethodWithProgress(fullClassName, methodInfo);
                            } catch (Exception e) {
                                log.error("Error when generating tests for method with signature " + paramTypes +
                                         " in " + className + " " + config.getProject().getArtifactId() + "\n" + e.getMessage());
                            }
                            break;
                        }
                    }
                }

                if (!methodFound) {
                    throw new IOException("Method with signature " + paramTypes +
                                         " in class " + fullClassName + " not found");
                }
            }

        } catch (IOException e) {
            log.warn("Method not found with signature: " + paramTypes +
                     " in " + className + " " + config.getProject().getArtifactId());
            return;
        }

        log.info(String.format("\n==========================\n[%s] Generation finished", config.pluginSign));

        Path testOutPutPath = config.getTestOutput();
        classNameProcessor.processJavaFiles(testOutPutPath);
        log.info(String.format("\n==========================\n[%s] Test processed", config.pluginSign));
        reportCoverageIfEnabled();
    }

    public void startClassTask(String className) throws IOException {
        if(granularity == null){
            granularity = Granularity.CLASS;
        }
        try {
            checkTargetFolder(config.getProject());
        } catch (RuntimeException e) {
            log.error(e.toString());
            return;
        }
        if (config.getProject().getPackaging().equals("pom")) {
            log.info(String.format("\n==========================\n[%s] Skip pom-packaging ...",config.pluginSign));
            return;
        }
        Phase phase = PhaseImpl.createPhase(config);
        phase.prepare();
        log.info(String.format("\n==========================\n[%s] Generating tests for class < " + className + " > ...",config.pluginSign));
        try {
            String fullClassName = getFullClassName(config, className);
            ClassInfo classInfo = AbstractRunner.getClassInfo(config, fullClassName);
            int runnableMethods = countRunnableMethods(classInfo);
            config.rebalanceThreadAllocation(1, runnableMethods);
            config.resetProgress(runnableMethods);
            printStatusWindow();
            preloadScoutDescriptionsIfNeeded(Collections.singletonList(fullClassName));
            this.runner.runClass(fullClassName);
        } catch (IOException e) {
            log.warn("Class not found: " + className + " in " + config.getProject().getArtifactId());
        }
        log.info(String.format("\n==========================\n[%s] Generation finished",config.pluginSign));

        Path testOutPutPath = config.getTestOutput();
        classNameProcessor.processJavaFiles(testOutPutPath);
        log.info(String.format("\n==========================\n[%s] Test processed", config.pluginSign));
        reportCoverageIfEnabled();
    }

    public void startProjectTask() throws IOException {

        String targetClass = normalizeTargetClass(config.getCname());
        if (!targetClass.isEmpty()) {
            startClassTask(targetClass);
            return;
        }


        if(granularity == null){
            granularity = Granularity.PROJECT;
        }
        Counter.generateMethodCSV(config);        
        Project project = config.getProject();
        try {
            checkTargetFolder(project);
        } catch (Exception e) {
            log.error(e.toString());
            return;
        }
        if (project.getPackaging().equals("pom")) {
            log.info(String.format("\n==========================\n[%s] Skip pom-packaging ...",config.pluginSign));
            return;
        }
        Phase phase = PhaseImpl.createPhase(config);
        phase.prepare();
        List<String> classNames = ProjectParser.scanProjectClasses(project);
        preloadScoutDescriptionsIfNeeded(classNames);

        try {
            int targetMethodCount = Counter.countMethod(config.getTmpOutput());
            config.rebalanceThreadAllocation(classNames.size(), targetMethodCount);
            config.resetProgress(targetMethodCount);
            printStatusWindow();
        } catch (IOException e) {
            log.error("Error when counting methods: " + e);
        }

        if (config.isEnableMultithreading() == true) {
            projectJob(classNames);
        } else {
            for (String className : classNames) {
                try {
                    String fullClassName = getFullClassName(config, className);
                    log.info(String.format("\n==========================\n[%s] Generating tests for class < ",config.pluginSign) + fullClassName + " > ...");
                    ClassInfo info = AbstractRunner.getClassInfo(config, fullClassName);
                    if (!Counter.filter(info)) {
                        config.getLogger().info("Skip class: " + fullClassName);
                        continue;
                    }

                    this.runner.runClass(fullClassName);
                } catch (IOException e) {
                    log.error(String.format("[%s] Generate tests for class ",config.pluginSign) + className + " failed: " + e);
                }
            }
        }

        log.info(String.format("\n==========================\n[%s] Generation finished",config.pluginSign));

        Path testOutPutPath = config.getTestOutput();
        classNameProcessor.processJavaFiles(testOutPutPath);
        log.info(String.format("\n==========================\n[%s] Test processed",config.pluginSign));
        reportCoverageIfEnabled();
    }

    private void reportCoverageIfEnabled() {
        if (config != null) {
            config.setCurrentStatusStep("Coverage");
            config.setStatusOutputPath(config.getTestOutput());
        }
        new CoverageSummaryReporter(config).reportIfEnabled();
    }

    private void runMethodWithProgress(String fullClassName, MethodInfo methodInfo) {
        try {
            this.runner.runMethod(fullClassName, methodInfo);
        } finally {
            config.markMethodCompleted();
            printStatusWindow();
        }
    }

    private void printStatusWindow() {
        statusWindow.print(config);
    }

    private int countTargetMethods(ClassInfo classInfo, String methodName) throws IOException {
        if (classInfo == null || methodName == null) {
            return 0;
        }
        int count = 0;
        if (methodName.matches("\\d+")) {
            for (String mSig : classInfo.methodSigs.keySet()) {
                if (methodName.equals(classInfo.methodSigs.get(mSig))) {
                    MethodInfo methodInfo = AbstractRunner.getMethodInfo(config, classInfo, mSig);
                    if (Counter.filter(methodInfo)) {
                        count++;
                    }
                }
            }
            return count;
        }
        for (String mSig : classInfo.methodSigs.keySet()) {
            if (mSig.split("\\(")[0].equals(methodName)) {
                MethodInfo methodInfo = AbstractRunner.getMethodInfo(config, classInfo, mSig);
                if (Counter.filter(methodInfo)) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countRunnableMethods(ClassInfo classInfo) throws IOException {
        if (classInfo == null || classInfo.methodSigs == null) {
            return 0;
        }
        int count = 0;
        for (String mSig : classInfo.methodSigs.keySet()) {
            MethodInfo methodInfo = AbstractRunner.getMethodInfo(config, classInfo, mSig);
            if (Counter.filter(methodInfo)) {
                count++;
            }
        }
        return count;
    }

    private void preloadScoutDescriptionsIfNeeded(List<String> classNames) {
        if (!config.isEnableMultithreading() || !"SCOUT".equalsIgnoreCase(config.getPhaseType())) {
            return;
        }
        Path stateBasePath = config.getScoutRunOutput() == null ? config.getTmpOutput() : config.getScoutRunOutput();
        if (stateBasePath == null) {
            return;
        }
        new ScoutDescriptionPreloader(config, new ScoutStateStore(stateBasePath)).preload(classNames);
    }

    public void projectJob(List<String> classNames) {
        if (usesDynamicMethodQueue()) {
            dynamicProjectMethodJob(classNames);
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(config.getClassThreads());
        List<Future<String>> futures = new ArrayList<>();
        for (String className : classNames) {
            Callable<String> callable = new Callable<String>() {
                @Override
                public String call() throws Exception {
                    config.markWorkerThreadStarted();
                    try {
                        String fullClassName = getFullClassName(config, className);
                        log.info(String.format("\n==========================\n[%s] Generating tests for class < ",config.pluginSign) + fullClassName + " > ...");
                        ClassInfo info = AbstractRunner.getClassInfo(config, fullClassName);
                        if (!Counter.filter(info)) {
                            return "Skip class: " + fullClassName;
                        }
                        runner.runClass(fullClassName);
                    } catch (IOException e) {
                        log.error(String.format("[%s] Generate tests for class ",config.pluginSign) + className + " failed: " + e);
                    } finally {
                        config.markWorkerThreadFinished();
                    }
                    return "Processed " + className;
                }
            };
            Future<String> future = executor.submit(callable);
            futures.add(future);
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                executor.shutdownNow();
            }
        });

        for (Future<String> future : futures) {
            try {
                String result = future.get();
                if (!config.isStatusOnlyOutput()) {
                    System.out.println(result);
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
    }

    private boolean usesDynamicMethodQueue() {
        return config != null
                && config.isEnableMultithreading();
    }

    private void dynamicProjectMethodJob(List<String> classNames) {
        final List<MethodWorkItem> workItems = collectMethodWorkItems(classNames);
        if (workItems.isEmpty()) {
            return;
        }
        List<MethodWorkItem> scheduledItems = MethodWorkScheduler.longestFirst(workItems);
        MethodWorkScheduler.run(scheduledItems, dynamicMethodWorkerCount(),
                new MethodWorkScheduler.Worker<MethodWorkItem>() {
                    @Override
                    public void run(MethodWorkItem item) {
                        config.markWorkerThreadStarted();
                        long startNanos = System.nanoTime();
                        try {
                            config.updateCurrentStatusMethod(item.fullClassName, item.methodInfo);
                            runner.runMethod(item.fullClassName, item.methodInfo);
                        } finally {
                            long elapsedNanos = System.nanoTime() - startNanos;
                            config.recordTiming("method", elapsedNanos);
                            config.recordTiming(methodTimingCategory(item.fullClassName, item.methodInfo), elapsedNanos);
                            config.markMethodCompleted();
                            printStatusWindow();
                            config.markWorkerThreadFinished();
                        }
                    }
                }, new MethodWorkScheduler.FailureHandler<MethodWorkItem>() {
                    @Override
                    public void onFailure(MethodWorkItem item, Exception failure) {
                        config.markRuntimeError();
                        log.error(String.format("[%s] Generate tests for method ", config.pluginSign)
                                + item.methodInfo.getMethodName() + " in class " + item.fullClassName
                                + " failed: " + failure);
                    }
                });
        mergeGeneratedTestsByClass(workItems);
    }

    private int dynamicMethodWorkerCount() {
        int maxThreads = Math.max(1, config.getMaxThreads());
        if (config.isStopWhenSuccess()) {
            return maxThreads;
        }
        return Math.max(1, maxThreads / Math.max(1, config.getTestNumber()));
    }

    private List<MethodWorkItem> collectMethodWorkItems(List<String> classNames) {
        List<MethodWorkItem> workItems = new ArrayList<MethodWorkItem>();
        for (String className : classNames) {
            try {
                String fullClassName = getFullClassName(config, className);
                log.info(String.format("\n==========================\n[%s] Queueing methods for class < ", config.pluginSign)
                        + fullClassName + " > ...");
                ClassInfo classInfo = AbstractRunner.getClassInfo(config, fullClassName);
                if (!Counter.filter(classInfo)) {
                    config.getLogger().info("Skip class: " + fullClassName);
                    continue;
                }
                for (String mSig : classInfo.methodSigs.keySet()) {
                    MethodInfo methodInfo = AbstractRunner.getMethodInfo(config, classInfo, mSig);
                    if (methodInfo == null) {
                        continue;
                    }
                    if (!Counter.filter(methodInfo)) {
                        config.getLogger().info("Skip method: " + mSig + " in class: " + fullClassName);
                        continue;
                    }
                    workItems.add(new MethodWorkItem(fullClassName, methodInfo,
                            config.averageTimingNanos(methodTimingCategory(fullClassName, methodInfo))));
                }
            } catch (IOException e) {
                log.error(String.format("[%s] Queue methods for class ", config.pluginSign)
                        + className + " failed: " + e);
            }
        }
        return workItems;
    }

    private void mergeGeneratedTestsByClass(List<MethodWorkItem> workItems) {
        if (!config.isEnableMerge()) {
            return;
        }
        List<String> mergedClasses = new ArrayList<String>();
        for (MethodWorkItem item : workItems) {
            if (mergedClasses.contains(item.fullClassName)) {
                continue;
            }
            mergedClasses.add(item.fullClassName);
            try {
                new TestClassMerger(config, item.fullClassName).mergeWithSuite();
            } catch (IOException e) {
                log.error(String.format("[%s] Merge tests for class ", config.pluginSign)
                        + item.fullClassName + " failed: " + e);
            }
        }
    }

    private static String methodTimingCategory(String fullClassName, MethodInfo methodInfo) {
        String signature = methodInfo == null ? "" : methodInfo.getMethodSignature();
        if (signature == null || signature.trim().isEmpty()) {
            signature = methodInfo == null ? "" : methodInfo.getMethodName();
        }
        return "method:" + (fullClassName == null ? "" : fullClassName.trim()) + "#" + (signature == null ? "" : signature.trim());
    }

    private static class MethodWorkItem implements MethodWorkScheduler.WeightedWork {
        private final String fullClassName;
        private final MethodInfo methodInfo;
        private final long estimatedNanos;

        MethodWorkItem(String fullClassName, MethodInfo methodInfo, long estimatedNanos) {
            this.fullClassName = fullClassName;
            this.methodInfo = methodInfo;
            this.estimatedNanos = Math.max(0L, estimatedNanos);
        }

        @Override
        public long estimatedNanos() {
            return estimatedNanos;
        }
    }

    public static String getFullClassName(Config config, String name) throws IOException {
        if (isFullName(name)) {
            return name;
        }
        Path classMapPath = config.getClassNameMapPath();
        Map<String, List<String>> classMap = config.getGSON().fromJson(new String(Files.readAllBytes(classMapPath), StandardCharsets.UTF_8), Map.class);
        if (classMap.containsKey(name)) {
            if (classMap.get(name).size() > 1) {
                throw new RuntimeException((String.format("[%s] Multiple classes Named ",config.pluginSign)) + name + ": " + classMap.get(name)
                        + " Please use full qualified name!");
            }
            return classMap.get(name).get(0);
        }
        return name;
    }

    public static boolean isFullName(String name) {
        if (name.contains(".")) {
            return true;
        }
        return false;
    }

    private static String normalizeTargetClass(String targetClass) {
        return targetClass == null ? "" : targetClass.trim();
    }

    /**
     * Check if the classes is compiled
     * @param project
     */
    public static void checkTargetFolder(Project project) {
        if (project.getPackaging().equals("pom")) {
            return;
        }

        if (!new File(project.getBuildPath().toString()).exists()) {
            throw new RuntimeException("In ProjectTestMojo.checkTargetFolder: " +
                    "The project is not compiled. Expected compiled classes at " +
                    project.getBuildPath().toString() +
                    ". Compile the project first (e.g. 'mvn install' or './gradlew classes').");
        }
    }
}
