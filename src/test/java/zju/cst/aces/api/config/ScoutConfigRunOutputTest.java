package zju.cst.aces.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zju.cst.aces.api.Project;
import zju.cst.aces.util.TestCompiler;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutConfigRunOutputTest {
    @TempDir
    Path tempDir;

    @Test
    void scoutConfigPlacesTmpDerivedOutputsUnderTimestampedRunDirectory() {
        Path baseTmp = tempDir.resolve("chatunitest-info");
        Config config = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project")))
                .phaseType("SCOUT")
                .tmpOutput(baseTmp)
                .build();

        Path runOutput = config.getTmpOutput();

        assertEquals(baseTmp.resolve("demo-artifact").resolve("scout-runs").resolve("gpt-3.5-turbo"),
                runOutput.getParent());
        assertTrue(runOutput.getFileName().toString().matches("\\d{8}-\\d{6}-\\d{3}(?:-\\d+)?"));
        assertEquals(runOutput, config.getScoutRunOutput());
        assertEquals(runOutput.resolve("build"), config.getCompileOutputPath());
        assertEquals(runOutput.resolve("class-info"), config.getParseOutput());
        assertEquals(runOutput.resolve("error-message"), config.getErrorOutput());
        assertEquals(runOutput.resolve("classNameMapping.json"), config.getClassNameMapPath());
        assertEquals(runOutput.resolve("symbolFrames.json"), config.getSymbolFramePath());
        assertEquals(runOutput.resolve("history" + config.getDate()), config.getHistoryPath());
        assertEquals(config.getCompileOutputPath().toFile(), TestCompiler.buildFolder);
    }

    @Test
    void nonScoutConfigKeepsTmpDerivedOutputsAtConfiguredBaseDirectory() {
        Path baseTmp = tempDir.resolve("chatunitest-info");
        Config config = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project")))
                .phaseType("HITS")
                .tmpOutput(baseTmp)
                .build();

        Path expected = baseTmp.resolve("demo-artifact");

        assertEquals(expected, config.getTmpOutput());
        assertNotEquals("scout-runs", config.getTmpOutput().getParent().getFileName().toString());
        assertEquals(expected.resolve("build"), config.getCompileOutputPath());
        assertEquals(expected.resolve("error-message"), config.getErrorOutput());
    }

    @Test
    void isolatedTestOutputPlacesExportsUnderTimestampedRunDirectory() {
        Path outputBase = tempDir.resolve("SCOUT");
        Config config = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project")))
                .phaseType("SCOUT")
                .testOutput(outputBase)
                .isolateTestOutputByRun(true)
                .build();

        Path runOutput = config.getTestOutput();

        assertEquals(outputBase.resolve("gpt-3.5-turbo"), runOutput.getParent());
        assertTrue(runOutput.getFileName().toString().matches("\\d{8}-\\d{6}-\\d{3}(?:-\\d+)?"));
        assertEquals(config.getTestOutput().toFile(), TestCompiler.testOutputFolder);
    }

    @Test
    void isolatedTestOutputUsesSanitizedModelNameInRunDirectory() {
        Path outputBase = tempDir.resolve("SCOUT");
        Config config = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project")))
                .phaseType("SCOUT")
                .model("codeqwen:v1.5-chat")
                .testOutput(outputBase)
                .isolateTestOutputByRun(true)
                .build();

        Path runOutput = config.getTestOutput();

        assertEquals(outputBase.resolve("codeqwen-v1.5-chat"), runOutput.getParent());
        assertTrue(runOutput.getFileName().toString().matches("\\d{8}-\\d{6}-\\d{3}(?:-\\d+)?"));
    }

    @Test
    void explicitTestOutputIsStableUnlessRunIsolationIsEnabled() {
        Path outputBase = tempDir.resolve("SCOUT");
        Config config = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project")))
                .phaseType("SCOUT")
                .testOutput(outputBase)
                .build();

        assertEquals(outputBase, config.getTestOutput());
    }

    @Test
    void apiKeyDefaultsToNoApiAndCanBeOverridden() {
        Config defaultConfig = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project-default")))
                .build();
        Config configured = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project-configured")))
                .apiKey("test-api-key")
                .build();

        assertEquals("NO_API", new ModelConfig.Builder().build().getApiKey());
        assertEquals("NO_API", defaultConfig.getApiKey());
        assertEquals("NO_API", defaultConfig.getRandomKey());
        assertEquals("test-api-key", configured.getApiKey());
        assertEquals("test-api-key", configured.getRandomKey());
    }

    @Test
    void reportCoverageDefaultsToTrueAndCanBeDisabled() {
        Config defaultConfig = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project-default")))
                .build();
        Config configured = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project-configured")))
                .reportCoverage(false)
                .build();

        assertEquals(true, defaultConfig.isReportCoverage());
        assertEquals(false, configured.isReportCoverage());
    }

    @Test
    void timingProfileAccumulatesCategoryTotalsAndRendersSummary() {
        Config config = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project")))
                .resourceProfileEnabled(true)
                .build();

        config.recordTiming("LLM", 1_000_000L);
        config.recordTiming("LLM", 2_000_000L);
        config.recordTiming("compile", 3_000_000L);

        String summary = config.formatTimingProfile();

        assertTrue(summary.contains("Timing Profile"));
        assertTrue(summary.contains("LLM"));
        assertTrue(summary.contains("count=2"));
        assertTrue(summary.contains("total=00:00:00.003"));
        assertTrue(summary.contains("compile"));
        assertTrue(summary.contains("count=1"));
    }

    @Test
    void timingProfileIsDisabledByDefault() {
        Config config = new Config.ConfigBuilder(new FakeProject(tempDir.resolve("project"))).build();

        config.recordTiming("LLM", 1_000_000L);

        assertTrue(config.formatTimingProfile().isEmpty());
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
        public List<String> getCompileSourceRoots() {
            return new ArrayList<>();
        }

        @Override
        public Path getArtifactPath() {
            return basedir.toPath().resolve("target").resolve("demo.jar");
        }

        @Override
        public Path getBuildPath() {
            return basedir.toPath().resolve("target");
        }

        @Override
        public List<String> getClassPaths() {
            return new ArrayList<>();
        }

        @Override
        public List<String> getDependencyPaths() {
            return new ArrayList<>();
        }
    }
}
