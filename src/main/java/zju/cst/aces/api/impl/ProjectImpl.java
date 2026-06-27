package zju.cst.aces.api.impl;

import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import zju.cst.aces.api.Project;
import zju.cst.aces.parser.ProjectParser;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;

import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.FileReader;

public class ProjectImpl implements Project {

    // MavenProject project;

    MavenProject project;

    Model model;
    File baseDir;

    List<String> modules;

    List<String> classPaths;
    List<String> srcRoots;
    List<String> dependencyPaths;

    public ProjectImpl(Model model, File baseDir) {
        this.model = model;
        this.baseDir = baseDir;

        this.modules = model.getModules();


        this.classPaths = collectClassPaths();
        this.srcRoots = collectSrcRoots();
        this.dependencyPaths = new ArrayList<>();;
    }

    
    public ProjectImpl(Model model, File baseDir, String dependencyPathDir) {
        this(model, baseDir, dependencyPathDir, resolveCoreProjectDir());
    }


    public ProjectImpl(Model model, File baseDir, String dependencyPathDir, File coreProjectDir) {
        this.model = model;
        this.baseDir = baseDir;

        this.modules = model.getModules();


        List<String> projectDependencyPaths = collectDependencyPaths(dependencyPathDir);
        this.classPaths = collectClassPaths();
        this.classPaths.addAll(projectDependencyPaths);
        this.srcRoots = collectSrcRoots();
        this.dependencyPaths = collectCoreDependencyPaths(coreProjectDir);


    }


    private static File resolveCoreProjectDir() {
        return ProjectPaths.resolveCoreProjectDir();
    }

    private List<String> collectSrcRoots() {
        List<String> result = new ArrayList<>();
        
        if (modules.size() == 0) {
            String srcDirPath = baseDir.getAbsolutePath() + "/src/main/java/";
            result.add(srcDirPath);

        } else {
            for (String module : modules) {
                String srcDirPath = baseDir.getAbsolutePath() + "/src/main/java/";
                result.add(srcDirPath);
            }
        }
        return result;
    }

    private List<String> collectClassPaths() {
        List<String> result = new ArrayList<>();
        String classDirPath = baseDir.getAbsolutePath() + "/target/classes";
        File dir = new File(classDirPath);

        result.add(dir.getAbsolutePath());
        
        // if (modules.size() == 0) {
        //     String classDirPath = baseDir.getAbsolutePath() + "/target/classes";
        //     File dir = new File(classDirPath);

        //     if (!dir.exists() || !dir.isDirectory()) {
        //         return result;
        //     }

        //     scanDirectory(dir, dir, result, ".class");

        // } else {
        //     for (String module : modules) {
        //         String classDirPath = baseDir.getAbsolutePath() + "/" + module + "/target/classes";
        //         File dir = new File(classDirPath);

        //         if (!dir.exists() || !dir.isDirectory()) {
        //             return result;
        //         }

        //         scanDirectory(dir, dir, result, ".class");

        //     }
        // }


        return result;
    }

    
    private List<String> collectDependencyPaths(String dependencyPaths) {
        List<String> result = new ArrayList<>();
        if (dependencyPaths == null) {
            return result;
        }

        
        // File dir = new File(dependencyPaths);
        // result.add(dir.getAbsolutePath());
        if (modules.size() == 0) {
            File dir = new File(dependencyPaths);

            if (!dir.exists() || !dir.isDirectory()) {
                return result;
            }

            scanDirectory(dir, dir, result, ".jar");

        } else {
            for (String module : modules) {
                File dir = new File(dependencyPaths);

                if (!dir.exists() || !dir.isDirectory()) {
                    return result;
                }

                scanDirectory(dir, dir, result, ".jar");

            }
        }

        return result;
    }

    private List<String> collectCoreDependencyPaths(File coreProjectDir) {
        return ProjectPaths.collectCoreDependencyPaths(coreProjectDir);
    }


    private void scanDirectory(File baseDir, File currentDir, List<String> result, String prefix) {
        ProjectPaths.scanDirectory(baseDir, currentDir, result, prefix);
    }

    public ProjectImpl(MavenProject project) {
        this.project = project;
        this.dependencyPaths = new ArrayList<>();
    }

    public ProjectImpl(MavenProject project, List<String> classPaths) {
        this.project = project;
        this.classPaths = classPaths;
        this.dependencyPaths = new ArrayList<>();
    }

    @Override
    public Project getParent() {
        // if (project.getParent() == null) {
        //     return null;
        // }

        // return new ProjectImpl(project.getParent());

        if (model.getParent() == null) return null;

        Parent parent = model.getParent();

        String parentPomPath = baseDir + "/" + parent.getRelativePath();

        try {
            File pomFile = new File(parentPomPath);
            File parentBaseDir = pomFile.getParentFile();
            MavenXpp3Reader reader = new MavenXpp3Reader();

            Model parentModel = reader.read(new java.io.FileReader(pomFile));

            return new ProjectImpl(parentModel, parentBaseDir);
            
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public File getBasedir() {
        return this.baseDir;
        // return project.getBasedir();
    }

    @Override
    public String getPackaging() {
        return model.getPackaging();
        // return project.getPackaging();
    }

    @Override
    public String getGroupId() {
        return model.getGroupId();
        // return project.getGroupId();
    }

    @Override
    public String getArtifactId() {
        return model.getArtifactId();
        // return project.getArtifactId();
    }

    @Override
    public List<String> getCompileSourceRoots() {
        return this.srcRoots;
        // return project.getCompileSourceRoots();
    }

    @Override
    public Path getArtifactPath() {
        String finalName = "";

        if (model.getBuild() != null && model.getBuild().getFinalName() != null) {
            finalName = model.getBuild().getFinalName() + ".jar";
        } else {
            finalName = model.getArtifactId() + "-" + model.getVersion() + ".jar";
        }

        return Paths.get(getBuildPath().toString() + "/" + finalName);

        // return Paths.get(project.getBuild().getDirectory()).resolve(project.getBuild().getFinalName() + ".jar");
    }

    @Override
    public Path getBuildPath() {
        if (model.getBuild() != null && model.getBuild().getDirectory() != null) {
            return Paths.get(model.getBuild().getDirectory());
        } else{
            return Paths.get(model.getProjectDirectory() + "/target/classes");
        }
        
        // return Paths.get(project.getBuild().getOutputDirectory());
    }

    @Override
    public List<String> getClassPaths() {

        return this.classPaths;
    }

    @Override
    public List<String> getDependencyPaths() {

        return this.dependencyPaths;
    }
}
