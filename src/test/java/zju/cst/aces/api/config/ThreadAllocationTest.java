package zju.cst.aces.api.config;

import org.junit.jupiter.api.Test;
import zju.cst.aces.api.Project;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadAllocationTest {
    @Test
    void favorsMethodThreadsForSingleLargeClass() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .maxThreads(8)
                .build();

        config.rebalanceThreadAllocation(1, 80);

        assertEquals(1, config.getClassThreads());
        assertEquals(8, config.getMethodThreads());
    }

    @Test
    void favorsClassThreadsForManySmallClasses() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .maxThreads(8)
                .build();

        config.rebalanceThreadAllocation(80, 80);

        assertEquals(8, config.getClassThreads());
        assertEquals(1, config.getMethodThreads());
    }

    @Test
    void keepsNestedThreadProductWithinMaxThreads() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .maxThreads(16)
                .build();

        config.rebalanceThreadAllocation(8, 32);

        assertEquals(8, config.getClassThreads());
        assertEquals(2, config.getMethodThreads());
        assertTrue(config.getClassThreads() * config.getMethodThreads() <= config.getMaxThreads());
    }

    @Test
    void leavesSingleThreadModeUnchanged() {
        Config config = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .maxThreads(8)
                .enableMultithreading(false)
                .build();

        config.rebalanceThreadAllocation(80, 80);

        assertEquals(1, config.getClassThreads());
        assertEquals(8, config.getMethodThreads());
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
