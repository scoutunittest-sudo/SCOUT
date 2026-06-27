package zju.cst.aces.status;

import org.junit.jupiter.api.Test;
import zju.cst.aces.api.Project;
import zju.cst.aces.api.config.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageStatusWindowTest {
    @Test
    void rendersCoverageOnlyStatusWithoutMethodOrLlmDetails() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .phaseType("SCOUT")
                .coverageOnlyMode(true)
                .build();
        config.resetStatusProgress(5, "Running coverage", Paths.get("/tmp/demo-project/SCOUT"));
        config.markMethodCompleted();
        config.markMethodCompleted();
        config.markValidCoverageTest();
        config.markValidCoverageTest();
        config.markValidCoverageTest();
        config.markCompilationError();
        config.markRuntimeError();

        String window = new CoverageStatusWindow().render(config);

        assertTrue(window.contains("ChatUniTest Coverage"));
        assertTrue(window.contains("Phase   : SCOUT / Running coverage"));
        assertTrue(window.contains("Tests   : 2 / 5"));
        assertTrue(window.contains("Valid tests    : 3"));
        assertTrue(window.contains("Compile errors : 1"));
        assertTrue(window.contains("Runtime errors : 1"));
        assertTrue(!window.contains("Methods"));
        assertTrue(!window.contains("LLM"));
        assertTrue(!window.contains("Exported tests"));
        assertTrue(!window.contains("Full line cov"));
    }

    @Test
    void printOverwritesExistingCoverageStatusFrame() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .coverageOnlyMode(true)
                .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CoverageStatusWindow statusWindow = new CoverageStatusWindow(new PrintStream(output), 0L, () -> 0L);

        statusWindow.printNow(config);
        config.resetStatusProgress(2, "Running coverage", Paths.get("/tmp/demo-project/SCOUT"));
        config.markMethodCompleted();
        statusWindow.printNow(config);

        String rendered = output.toString();
        assertEquals(2, countOccurrences(rendered, "\u001B[2J\u001B[H"));
        assertTrue(rendered.contains("Tests   : 1 / 2"));
    }

    @Test
    void eventPrintDoesNotRepaintCoverageStatusWindow() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .coverageOnlyMode(true)
                .build();
        config.resetStatusProgress(4, "Running coverage", Paths.get("/tmp/demo-project/SCOUT"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicLong now = new AtomicLong(1_000L);
        CoverageStatusWindow statusWindow = new CoverageStatusWindow(new PrintStream(output), 200L, now::get);

        statusWindow.print(config);
        config.markMethodCompleted();
        statusWindow.print(config);
        now.addAndGet(201L);
        statusWindow.print(config);

        String rendered = output.toString();
        assertEquals(0, countOccurrences(rendered, "\u001B[2J\u001B[H"));
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
