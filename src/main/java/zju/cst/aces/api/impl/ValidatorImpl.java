package zju.cst.aces.api.impl;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import lombok.Data;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.api.Validator;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.util.TestCompiler;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Data
public class ValidatorImpl implements Validator {

    TestCompiler compiler;
    private static final long DEFAULT_TIMEOUT = 2L;
    private static final TimeUnit DEFAULT_TIMEOUT_UNIT = TimeUnit.MINUTES;
    private long timeout;
    private TimeUnit timeoutUnit;

    public ValidatorImpl(Path testOutputPath, Path compileOutputPath, Path targetPath, List<String> classpathElements) {
        this(new TestCompiler(testOutputPath, compileOutputPath, targetPath, classpathElements),
                DEFAULT_TIMEOUT, DEFAULT_TIMEOUT_UNIT);
    }

    
    public ValidatorImpl(Path testOutputPath, Path compileOutputPath, Path targetPath, List<String> classpathElements, List<String> dependList) {
        this(new TestCompiler(testOutputPath, compileOutputPath, targetPath, classpathElements, dependList),
                DEFAULT_TIMEOUT, DEFAULT_TIMEOUT_UNIT);
    }

    ValidatorImpl(TestCompiler compiler, long timeout, TimeUnit timeoutUnit) {
        this.compiler = compiler;
        this.timeout = timeout;
        this.timeoutUnit = timeoutUnit;
    }

    @Override
    public boolean syntacticValidate(String code) {
        try {
            StaticJavaParser.parse(code);
            return true;
        } catch (ParseProblemException e) {
            return false;
        }
    }

    @Override
    public boolean semanticValidate(String code, String className, Path outputPath, PromptInfo promptInfo) {
        return runWithTimeout(() -> compiler.compileTest(code, className, outputPath, promptInfo), false);
    }

    @Override
    public boolean runtimeValidate(String fullTestName) {
        TestExecutionSummary summary = runWithTimeout(() -> compiler.executeTest(fullTestName), null);
        return summary != null && summary.getTestsFailedCount() == 0;
    }

    @Override
    public boolean compile(String className, Path outputPath, PromptInfo promptInfo) {
        return runWithTimeout(() -> compiler.compileTest(className, outputPath, promptInfo), false);
    }

    @Override
    public TestExecutionSummary execute(String fullTestName) {
        return runWithTimeout(() -> compiler.executeTest(fullTestName), null);
    }

    private <T> T runWithTimeout(Callable<T> task, T fallback) {
        ExecutorService executor = Executors.newSingleThreadExecutor(timeoutThreadFactory());
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeout, timeoutUnit);
        } catch (TimeoutException e) {
            future.cancel(true);
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException e) {
            return fallback;
        } finally {
            executor.shutdownNow();
        }
    }

    private ThreadFactory timeoutThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "chatunitest-validator-timeout");
            thread.setDaemon(true);
            return thread;
        };
    }
}
