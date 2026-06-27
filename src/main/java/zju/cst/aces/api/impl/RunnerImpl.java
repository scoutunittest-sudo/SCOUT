package zju.cst.aces.api.impl;

import zju.cst.aces.api.Runner;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.prompt.PromptFile;
import zju.cst.aces.runner.ClassRunner;
import zju.cst.aces.runner.MethodRunner;
import zju.cst.aces.runner.solution_runner.ChatTesterRunner;
import zju.cst.aces.runner.solution_runner.HITSRunner;
import zju.cst.aces.runner.solution_runner.MUTAPRunner;
import zju.cst.aces.progress.MethodProgressTracker;

import java.io.IOException;
import java.time.LocalTime;

public class RunnerImpl implements Runner {
    Config config;

    int timeout;
    
    long startTime;

    
    public RunnerImpl(Config config, int timeout, long startTime) {
        this.config = config;
        this.timeout = -1;
        this.startTime = startTime;
    }

    public RunnerImpl(Config config) {
        this.config = config;
        this.timeout = -1;
        this.startTime = -1;
    }

    public void runClass(String fullClassName) {
        try {
            new ClassRunner(config, fullClassName).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void runMethod(String fullClassName, MethodInfo methodInfo) {
        try {
            selectRunner(config.getPhaseType(), fullClassName, methodInfo);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void selectRunner(String phaseType, String fullClassName, MethodInfo methodInfo) throws IOException {
        MethodProgressTracker tracker = config.getProgressTracker();
        if (tracker != null) {
            if (tracker.shouldSkip(fullClassName, methodInfo)) {
                config.getLogger().info("Resume skip (already attempted): "
                        + MethodProgressTracker.buildKey(fullClassName, methodInfo));
                return;
            }
            tracker.markStarted(fullClassName, methodInfo, phaseType);
        }
        String status = "ok";
        try {
            // Map templateName to a specific PromptFile enum constant
            switch (phaseType) {
                case "CHATTESTER":
                    new ChatTesterRunner(config, fullClassName, methodInfo, this.timeout, this.startTime).start();
                    break;
                case "HITS":
                    new HITSRunner(config, fullClassName, methodInfo, this.timeout, this.startTime).start();
                    break;
                case "MUTAP":
                    new MUTAPRunner(config, fullClassName, methodInfo, this.timeout, this.startTime).start();
                    break;
                default:
                    new MethodRunner(config, fullClassName, methodInfo, this.timeout, this.startTime).start();
                    break;
            }
        } catch (RuntimeException | IOException | Error e) {
            status = "error";
            throw e;
        } finally {
            if (tracker != null) {
                tracker.markFinished(fullClassName, methodInfo, phaseType, status);
            }
        }
    }
}
