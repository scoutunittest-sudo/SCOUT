package zju.cst.aces.api.impl;

import zju.cst.aces.api.Project;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link Project} for Gradle projects (no pom.xml). Values come from standard
 * Gradle layout and defaults instead of a Maven model: sources at
 * {@code src/main/java}, compiled classes at {@code build/classes/java/main},
 * dependency jars from the supplied {@code --lib} directory, and the
 * chatunitest-core {@code dep/} jars for running generated tests.
 */
public class GradleProjectImpl implements Project {

    private final File baseDir;
    private final List<String> classPaths;
    private final List<String> srcRoots;
    private final List<String> dependencyPaths;

    public GradleProjectImpl(File baseDir, String dependencyPathDir) {
        this(baseDir, dependencyPathDir, ProjectPaths.resolveCoreProjectDir());
    }

    public GradleProjectImpl(File baseDir, String dependencyPathDir, File coreProjectDir) {
        this.baseDir = baseDir;

        this.srcRoots = new ArrayList<>();
        this.srcRoots.add(baseDir.getAbsolutePath() + "/src/main/java/");

        this.classPaths = new ArrayList<>();
        this.classPaths.add(buildClassesDir().toString());
        this.classPaths.addAll(ProjectPaths.collectJars(dependencyPathDir));

        this.dependencyPaths = ProjectPaths.collectCoreDependencyPaths(coreProjectDir);
    }

    private Path buildClassesDir() {
        return Paths.get(baseDir.getAbsolutePath(), "build", "classes", "java", "main");
    }

    @Override
    public Project getParent() {
        return null;
    }

    @Override
    public File getBasedir() {
        return baseDir;
    }

    @Override
    public String getPackaging() {
        return "jar";
    }

    @Override
    public String getGroupId() {
        return "";
    }

    @Override
    public String getArtifactId() {
        return baseDir.getName();
    }

    @Override
    public List<String> getCompileSourceRoots() {
        return srcRoots;
    }

    @Override
    public Path getArtifactPath() {
        return Paths.get(getBuildPath().toString(), getArtifactId() + "-unknown.jar");
    }

    @Override
    public Path getBuildPath() {
        return buildClassesDir();
    }

    @Override
    public List<String> getClassPaths() {
        return classPaths;
    }

    @Override
    public List<String> getDependencyPaths() {
        return dependencyPaths;
    }
}
