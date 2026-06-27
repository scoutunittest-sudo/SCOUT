package zju.cst.aces.status;

import org.junit.jupiter.api.Test;
import zju.cst.aces.api.Project;
import zju.cst.aces.api.impl.StatusOnlyLogger;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.MethodInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusWindowTest {
    @Test
    void rendersRunContextProgressAndValidMethodCount() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .model("code-llama")
                .phaseType("SCOUT")
                .enableMultithreading(false)
                .reportCoverage(true)
                .build();
        config.resetProgress(10);
        config.markMethodCompleted();
        config.markMethodCompleted();
        config.markMethodWithValidTest("example.Target", method("value(int)"));
        config.setFullyCoveredMethodCount(2);
        config.markCompilationError();
        config.markRuntimeError();
        config.markExportedTest();
        config.markTimeout();
        config.markLlmCallAttempt();
        config.markLlmCallAttempt();
        config.markLlmCallSuccess();
        config.setCurrentStatusStep("Compiling");
        config.setCurrentStatusMethod("example.Target#value(int)");
        config.setStatusStartTimeMillis(System.currentTimeMillis() - 125_000);
        config.setStatusOutputPath(Paths.get("/tmp/SCOUT/code-llama/20260624-153012-123"));

        String window = new StatusWindow().render(config);

        assertTrue(window.contains("LLM     : code-llama"));
        assertTrue(window.contains("Phase   : SCOUT / Compiling"));
        assertTrue(window.contains("Mode    : single-thread"));
        assertTrue(window.contains("Coverage: report on"));
        assertTrue(window.contains("Project : /tmp/demo-project"));
        assertTrue(window.contains("Methods : 2 / 10"));
        assertTrue(window.contains("Current : example.Target#value(int)"));
        assertTrue(window.contains("Time    :"));
        assertTrue(window.contains("Valid methods : 1"));
        assertTrue(window.contains("Full line cov : 2"));
        assertTrue(window.contains("Exported tests : 1"));
        assertTrue(window.contains("Compile errors : 1"));
        assertTrue(window.contains("Runtime errors : 1"));
        assertTrue(window.contains("Timeouts       : 1"));
        assertTrue(window.contains("LLM attempts   : 2"));
        assertTrue(window.contains("LLM successes  : 1"));
        assertTrue(window.contains("Output  : /tmp/SCOUT/code-llama/20260624-153012-123"));
        assertTrue(window.contains("[####"));
        assertTrue(window.contains("\u001B[32m"));
        assertTrue(window.contains("\u001B[31m"));
    }

    @Test
    void hidesCurrentStepWhenMultithreadingIsEnabled() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .phaseType("SCOUT")
                .enableMultithreading(true)
                .maxThreads(8)
                .reportCoverage(false)
                .build();
        config.resetProgress(4);
        config.setCurrentStatusStep("Running");
        config.setCurrentStatusMethod("example.Target#value(int)");
        config.markWorkerThreadStarted();
        config.markWorkerThreadStarted();

        String window = new StatusWindow().render(config);

        assertTrue(window.contains("Phase   : SCOUT"));
        assertTrue(!window.contains("SCOUT / Running"));
        assertTrue(!window.contains("Current :"));
        assertTrue(!window.contains("example.Target#value(int)"));
        assertTrue(window.contains("Mode    : multithreading"));
        assertTrue(window.contains("Threads : 2 / 8"));
        assertTrue(window.contains("class 1, method 8"));
        assertTrue(!window.contains("Limits  :"));
        assertTrue(!window.contains("llm 0/"));
        assertTrue(window.contains("Coverage: report off"));
    }

    @Test
    void rendersResourceLimitsOnlyWhenResourceProfileIsEnabled() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .phaseType("SCOUT")
                .enableMultithreading(true)
                .resourceProfileEnabled(true)
                .maxThreads(8)
                .llmConcurrency(3)
                .compileConcurrency(2)
                .runConcurrency(4)
                .coverageConcurrency(1)
                .build();

        String window = new StatusWindow().render(config);

        assertTrue(window.contains("Limits  : llm 0/3, compile 0/2"));
        assertTrue(window.contains("run 0/4, coverage 0/1"));
    }

    @Test
    void rendersCoverageOnlyProgressAsTests() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .phaseType("SCOUT")
                .enableMultithreading(false)
                .reportCoverage(true)
                .build();
        config.resetProgress(5);
        config.setCurrentStatusStep("Coverage");
        config.markMethodCompleted();
        config.markMethodCompleted();

        String window = new StatusWindow().render(config);

        assertTrue(window.contains("Phase   : SCOUT / Coverage"));
        assertTrue(window.contains("Tests   : 2 / 5"));
        assertTrue(window.contains("[########"));
    }

    @Test
    void countsOneValidMethodOnlyOnce() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project"))).build();
        MethodInfo methodInfo = method("value(int)");

        config.markMethodWithValidTest("example.Target", methodInfo);
        config.markMethodWithValidTest("example.Target", methodInfo);

        assertEquals(1, config.getValidUnitTestMethodCount().get());
    }

    @Test
    void resetsErrorCountersWithProgress() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project"))).build();
        config.markCompilationError();
        config.markRuntimeError();
        config.markExportedTest();
        config.markTimeout();

        config.resetProgress(3);

        assertEquals(0, config.getCompilationErrorCount().get());
        assertEquals(0, config.getRuntimeErrorCount().get());
        assertEquals(0, config.getExportedTestCount().get());
        assertEquals(0, config.getTimeoutCount().get());
    }

    @Test
    void statusOnlyOutputUsesSilentLoggerButStatusWindowStillPrints() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .statusOnlyOutput(true)
                .build();

        assertTrue(config.isStatusOnlyOutput());
        assertTrue(config.getLogger() instanceof StatusOnlyLogger);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        config.getLogger().info("ordinary log");
        config.getLogger().warn("warning log");
        config.getLogger().error("error log");
        new StatusWindow(new PrintStream(output)).printNow(config);

        String rendered = output.toString();
        assertTrue(rendered.contains("ChatUniTest Status"));
        assertTrue(!rendered.contains("ordinary log"));
        assertTrue(!rendered.contains("warning log"));
        assertTrue(!rendered.contains("error log"));
    }

    @Test
    void printNowOverwritesExistingStatusWindowFrame() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project"))).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StatusWindow statusWindow = new StatusWindow(new PrintStream(output), 0L, () -> 0L);

        statusWindow.printNow(config);
        config.resetProgress(4);
        config.markMethodCompleted();
        statusWindow.printNow(config);

        String rendered = output.toString();
        assertEquals(2, countOccurrences(rendered, "\u001B[2J\u001B[H"));
        assertTrue(rendered.contains("Methods : 1 / 4"));
    }

    @Test
    void eventPrintDoesNotRepaintStatusWindow() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project"))).build();
        config.resetProgress(4);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicLong now = new AtomicLong(1_000L);
        StatusWindow statusWindow = new StatusWindow(new PrintStream(output), 200L, now::get);

        statusWindow.print(config);
        config.markMethodCompleted();
        statusWindow.print(config);
        now.addAndGet(201L);
        statusWindow.print(config);

        String rendered = output.toString();
        assertEquals(0, countOccurrences(rendered, "\u001B[2J\u001B[H"));
    }

    private MethodInfo method(String signature) {
        MethodInfo methodInfo = new MethodInfo("Target", "value", "", signature,
                "", Collections.emptyList(), Collections.emptyMap(), "", "", "");
        methodInfo.setPublic(true);
        return methodInfo;
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static class FakeProject implements Project {
        private final File basedir;

        private FakeProject(Path basedir) {
            this.basedir = basedir.toFile();
        }

        @Override
        public Project getParent() {
            return null;
        }

        @Override
        public File getBasedir() {
            return basedir;
        }

        @Override
        public String getPackaging() {
            return "jar";
        }

        @Override
        public String getGroupId() {
            return "demo";
        }

        @Override
        public String getArtifactId() {
            return "demo-artifact";
        }

        @Override
        public java.util.List<String> getCompileSourceRoots() {
            return new ArrayList<>();
        }

        @Override
        public Path getArtifactPath() {
            return basedir.toPath().resolve("target/demo.jar");
        }

        @Override
        public Path getBuildPath() {
            return basedir.toPath().resolve("target/classes");
        }

        @Override
        public java.util.List<String> getClassPaths() {
            return new ArrayList<>();
        }

        @Override
        public java.util.List<String> getDependencyPaths() {
            return new ArrayList<>();
        }
    }
}
