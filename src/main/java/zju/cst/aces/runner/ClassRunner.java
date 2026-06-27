package zju.cst.aces.runner;

import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.runner.solution_runner.ChatTesterRunner;
import zju.cst.aces.runner.solution_runner.HITSRunner;
import zju.cst.aces.runner.solution_runner.MUTAPRunner;
import zju.cst.aces.progress.MethodProgressTracker;
import zju.cst.aces.status.StatusWindow;
import zju.cst.aces.util.Counter;
import zju.cst.aces.util.TestClassMerger;
import zju.cst.aces.util.ClassNameProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class ClassRunner extends AbstractRunner {
    private final StatusWindow statusWindow = new StatusWindow();
    public ClassInfo classInfo;
    public File infoDir;
    public int index;

    long startTime;
    int timeout;

    public ClassRunner(Config config, String fullClassName, int timeout, long startTime) throws IOException {
        super(config, fullClassName);
        infoDir = config.getParseOutput().resolve(fullClassName.replace(".", File.separator)).toFile();
        if (!infoDir.isDirectory()) {
            config.getLogger().warn("Error: " + fullClassName + " no parsed info found");
        }
        File classInfoFile = new File(infoDir + File.separator + "class.json");
        classInfo = GSON.fromJson(new String(Files.readAllBytes(classInfoFile.toPath()), StandardCharsets.UTF_8), ClassInfo.class);
        this.timeout = timeout;
        this.startTime = startTime;
    }


    public ClassRunner(Config config, String fullClassName) throws IOException {
        super(config, fullClassName);
        infoDir = config.getParseOutput().resolve(fullClassName.replace(".", File.separator)).toFile();
        if (!infoDir.isDirectory()) {
            config.getLogger().warn("Error: " + fullClassName + " no parsed info found");
        }
        File classInfoFile = new File(infoDir + File.separator + "class.json");
        classInfo = GSON.fromJson(new String(Files.readAllBytes(classInfoFile.toPath()), StandardCharsets.UTF_8), ClassInfo.class);

        this.timeout = -1;
        this.startTime = -1;
    }

    @Override
    public void start() throws IOException {
        if (config.isEnableMultithreading() == true) {
            methodJob();
        } else {
            for (String mSig : classInfo.methodSigs.keySet()) {




                MethodInfo methodInfo = getMethodInfo(config, classInfo, mSig);
                if (!Counter.filter(methodInfo)) {
                    config.getLogger().info("Skip method: " + mSig + " in class: " + fullClassName);
                    continue;
                }
//                new MethodRunner(config, fullClassName, methodInfo).start();
                config.updateCurrentStatusMethod(fullClassName, methodInfo);
                selectRunner(config.getPhaseType(), fullClassName, methodInfo);
                config.markMethodCompleted();
                statusWindow.print(config);
            }
        }
        if (config.isEnableMerge()) {
            new TestClassMerger(config, fullClassName).mergeWithSuite();
        }
    }

    public void methodJob() {
        ExecutorService executor = Executors.newFixedThreadPool(config.getMethodThreads());
        List<Future<String>> futures = new ArrayList<>();
        for (String mSig : classInfo.methodSigs.keySet()) {
            Callable<String> callable = new Callable<String>() {
                @Override
                public String call() throws Exception {
                    config.markWorkerThreadStarted();
                    try {
                        MethodInfo methodInfo = getMethodInfo(config, classInfo, mSig);
                        if (methodInfo == null) {
                            return "No parsed info found for " + mSig + " in " + fullClassName;
                        }
                        if (!Counter.filter(methodInfo)) {
                            return "Skip method: " + mSig + " in class: " + fullClassName;
                        }
//                    new MethodRunner(config, fullClassName, methodInfo).start();
                        config.updateCurrentStatusMethod(fullClassName, methodInfo);
                        selectRunner(config.getPhaseType(), fullClassName, methodInfo);
                        config.markMethodCompleted();
                        statusWindow.print(config);
                        return "Processed " + mSig;
                    } finally {
                        config.markWorkerThreadFinished();
                    }
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

    public void selectRunner(String runnerType, String fullClassName, MethodInfo methodInfo) throws IOException {
        MethodProgressTracker tracker = config.getProgressTracker();
        if (tracker != null) {
            if (tracker.shouldSkip(fullClassName, methodInfo)) {
                config.getLogger().info("Resume skip (already attempted): "
                        + MethodProgressTracker.buildKey(fullClassName, methodInfo));
                return;
            }
            tracker.markStarted(fullClassName, methodInfo, runnerType);
        }
        String status = "ok";
        try {
            // Map templateName to a specific PromptFile enum constant
            switch (runnerType) {
                case "CHATTESTER":
                    new ChatTesterRunner(config, fullClassName, methodInfo, timeout, startTime).start();
                    break;
                case "HITS":
                    new HITSRunner(config, fullClassName, methodInfo, timeout, startTime).start();
                    break;
                case "MUTAP":
                    new MUTAPRunner(config, fullClassName, methodInfo, timeout, startTime).start();
                    break;
                default:
                    new MethodRunner(config, fullClassName, methodInfo, timeout, startTime).start();
                    break;
            }
        } catch (RuntimeException | IOException | Error e) {
            status = "error";
            throw e;
        } finally {
            if (tracker != null) {
                tracker.markFinished(fullClassName, methodInfo, runnerType, status);
            }
        }
    }
}
