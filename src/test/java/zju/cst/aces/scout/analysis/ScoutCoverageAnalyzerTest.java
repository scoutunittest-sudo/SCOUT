package zju.cst.aces.scout.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;
import zju.cst.aces.api.Project;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.dto.TestMessage;

import java.util.Collections;
import java.io.File;
import java.util.HashMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutCoverageAnalyzerTest {
    @Test
    void marksCoverageResultInvalidWhenJUnitSummaryReportsRuntimeFailure() {
        ScoutCoverageAnalyzer analyzer = new ScoutCoverageAnalyzer(config(),
                (testSourceCode, targetTestName, targetClassName, methodSignature,
                 targetClassCompiledDir, targetClassSourceDir, dependencies) -> {
                    Map<String, Object> raw = new HashMap<String, Object>();
                    raw.put("instructionCoverage", 50.0);
                    raw.put("branchCoverage", 50.0);
                    raw.put("lineCoverage", 50.0);
                    raw.put("methodCode", "int getValue() { return 1; }");
                    raw.put("uncoveredLines", Collections.emptyList());
                    raw.put("testsFailedCount", 1L);
                    raw.put("testsSucceededCount", 0L);
                    raw.put("failureMessages", Collections.singletonList("assertion failed"));
                    return raw;
                });

        ScoutCoverageResult result = analyzer.analyze("class SampleTest {}", "demo.SampleTest", promptInfo());

        assertFalse(result.isValidTest());
        assertEquals(TestMessage.ErrorType.RUNTIME_ERROR, result.getValidationError().getErrorType());
        assertTrue(result.getValidationError().getErrorMessage().get(0).contains("assertion failed"));
    }

    private PromptInfo promptInfo() {
        PromptInfo promptInfo = new PromptInfo(false, "demo.Sample", "getValue", "getValue()");
        CompilationUnit unit = StaticJavaParser.parse("package demo; class Sample { int getValue() { return 1; } }");
        ClassOrInterfaceDeclaration type = unit.getClassByName("Sample").get();
        ClassInfo classInfo = new ClassInfo(unit, type, 0, "class Sample",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap(), Collections.emptyList(), false,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), "");
        classInfo.setFullClassName("demo.Sample");
        classInfo.setCode(unit.toString(), type.toString());
        promptInfo.setClassInfo(classInfo);
        return promptInfo;
    }

    private Config config() {
        Config config = new Config();
        config.setProject(new FakeProject(Paths.get("/tmp/demo-project")));
        return config;
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
            return Collections.singletonList(basedir.toPath().resolve("src/main/java").toString());
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
            return Collections.emptyList();
        }

        @Override
        public java.util.List<String> getDependencyPaths() {
            return Collections.emptyList();
        }
    }
}
