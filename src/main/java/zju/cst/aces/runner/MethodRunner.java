package zju.cst.aces.runner;

import zju.cst.aces.api.phase.Phase;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.obfuscator.Obfuscator;
import zju.cst.aces.dto.*;
import zju.cst.aces.runner.ClassRunner;
import zju.cst.aces.util.JsonResponseProcessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class MethodRunner extends ClassRunner {

    public MethodInfo methodInfo;

    long startTime = 0;
    int timeout = -1;

    public MethodRunner(Config config, String fullClassName, MethodInfo methodInfo, int timeout, long startTime) throws IOException {
        super(config, fullClassName);
        this.methodInfo = methodInfo;
        this.timeout = timeout;
        this.startTime = startTime;
    }

    public MethodRunner(Config config, String fullClassName, MethodInfo methodInfo) throws IOException {
        super(config, fullClassName);
        this.methodInfo = methodInfo;
        this.timeout = -1;
        this.startTime = 0;
    }

    @Override
    public void start() throws IOException {
        if (!config.isStopWhenSuccess() && config.isEnableMultithreading()) {
            ExecutorService executor = Executors.newFixedThreadPool(config.getTestNumber());
            List<Future<String>> futures = new ArrayList<>();
            for (int num = 0; num < config.getTestNumber(); num++) {
                int finalNum = num;
                Callable<String> callable = () -> {
                    config.markWorkerThreadStarted();
                    try {
                        startRounds(finalNum);
                        return "";
                    } finally {
                        config.markWorkerThreadFinished();
                    }
                };
                Future<String> future = executor.submit(callable);
                futures.add(future);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdownNow));

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
        } else {
            for (int num = 0; num < config.getTestNumber(); num++) {
                boolean result = startRounds(num); //todo
                if (result && config.isStopWhenSuccess()) {
                    break;
                }
            }
        }
    }

    public boolean startRounds(final int num) throws IOException {
        config.updateCurrentStatusMethod(fullClassName, methodInfo);

        long currentTime = System.currentTimeMillis();

        long elapsed = currentTime - startTime;

        if (this.timeout != -1 && elapsed / 1000 >= startTime) {
            System.exit(1);
        }

        Phase phase = PhaseImpl.createPhase(config);

        // Prompt Construction Phase
        config.setCurrentStatusStep("Prompting");
        PromptConstructorImpl pc = phase.generatePrompt(classInfo, methodInfo,num);
        PromptInfo promptInfo = pc.getPromptInfo();
        promptInfo.setRound(0);

        // Test Generation Phase
        config.setCurrentStatusStep("Generating");
        phase.generateTest(pc);

        // Validation
        config.setCurrentStatusStep("Validating");
        if (phase.validateTest(pc)) {
            exportRecord(pc.getPromptInfo(), classInfo, num);

            return true;
        }

        // Validation and Repair Phase
        for (int rounds = 1; rounds < config.getMaxRounds(); rounds++) {

            promptInfo.setRound(rounds);

            // Repair
            config.setCurrentStatusStep("Repairing");
            phase.repairTest(pc);

            // Validation and process
            config.setCurrentStatusStep("Validating");
            if (phase.validateTest(pc)) { // if passed validation
                exportRecord(pc.getPromptInfo(), classInfo, num);
                return true;
            }

        }

        exportRecord(pc.getPromptInfo(), classInfo, num);
        return false;
    }
}
