package zju.cst.aces.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zju.cst.aces.api.Project;
import zju.cst.aces.api.config.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestClassMergerTest {
    @TempDir
    Path tempDir;

    @Test
    void writesMergedSuiteUnderDedicatedTestMergedDirectory() throws Exception {
        Path testOutput = tempDir.resolve("tests");
        Path packageDir = testOutput.resolve("demo");
        Files.createDirectories(packageDir);
        Files.write(packageDir.resolve("Target_getValue_0_Test.java"),
                "package demo; class Target_getValue_0_Test {}".getBytes(StandardCharsets.UTF_8));
        Files.write(packageDir.resolve("Target_size_0_Test.java"),
                "package demo; class Target_size_0_Test {}".getBytes(StandardCharsets.UTF_8));
        Config config = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project")))
                .testOutput(testOutput)
                .build();

        boolean merged = new TestClassMerger(config, "demo.Target").mergeWithSuite();

        Path mergedSuite = testOutput.resolve("test_merged/demo/Target_Suite.java");
        assertTrue(merged);
        assertTrue(Files.exists(mergedSuite));
        assertFalse(Files.exists(packageDir.resolve("Target_Suite.java")));
        String suiteCode = new String(Files.readAllBytes(mergedSuite), StandardCharsets.UTF_8);
        assertTrue(suiteCode.contains("package demo;"));
        assertTrue(suiteCode.contains("Target_getValue_0_Test.class"));
        assertTrue(suiteCode.contains("Target_size_0_Test.class"));
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
