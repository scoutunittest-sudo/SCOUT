package zju.cst.aces.api.config;

import org.junit.jupiter.api.Test;
import zju.cst.aces.api.Project;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLimiterTest {
    @Test
    void resourceLimiterTracksActiveAndConfiguredLimits() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .resourceProfileEnabled(true)
                .maxThreads(8)
                .llmConcurrency(3)
                .compileConcurrency(2)
                .runConcurrency(4)
                .coverageConcurrency(1)
                .build();

        Config.ResourceLease first = config.acquireResource("llm");
        Config.ResourceLease second = config.acquireResource("llm");

        assertEquals(2, config.getActiveResourceCount("llm"));
        assertEquals(3, config.getResourceLimit("llm"));
        assertTrue(config.formatResourceLimits().contains("llm 2/3"));

        first.close();
        assertEquals(1, config.getActiveResourceCount("llm"));
        second.close();
        assertEquals(0, config.getActiveResourceCount("llm"));
    }

    @Test
    void resourceLimiterDefaultsAreDerivedFromMaxThreads() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .resourceProfileEnabled(true)
                .maxThreads(8)
                .build();

        assertEquals(4, config.getResourceLimit("llm"));
        assertTrue(config.getResourceLimit("compile") >= 1);
        assertTrue(config.getResourceLimit("run") >= 1);
        assertTrue(config.getResourceLimit("coverage") >= 1);
    }

    @Test
    void resourceLimiterIsDisabledByDefault() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .llmConcurrency(1)
                .build();

        Config.ResourceLease lease = config.acquireResource("llm");

        assertEquals(0, config.getActiveResourceCount("llm"));
        assertEquals(0, config.getResourceLimit("llm"));
        lease.close();
        assertEquals(0, config.getActiveResourceCount("llm"));
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
            return new ArrayList<String>();
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
            return new ArrayList<String>();
        }

        @Override
        public java.util.List<String> getDependencyPaths() {
            return new ArrayList<String>();
        }
    }
}
