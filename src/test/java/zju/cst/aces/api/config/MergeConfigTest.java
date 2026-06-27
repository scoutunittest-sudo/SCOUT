package zju.cst.aces.api.config;

import org.junit.jupiter.api.Test;
import zju.cst.aces.api.Project;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeConfigTest {
    @Test
    void mergeIsDisabledByDefaultAndCanBeEnabled() {
        Config defaultConfig = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project"))).build();
        Config mergeConfig = new Config.ConfigBuilder(new FakeProject(Paths.get("/tmp/demo-project")))
                .enableMerge(true)
                .build();

        assertFalse(defaultConfig.isEnableMerge());
        assertTrue(mergeConfig.isEnableMerge());
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
