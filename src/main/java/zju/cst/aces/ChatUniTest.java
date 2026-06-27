package zju.cst.aces;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import lombok.Data;
import zju.cst.aces.api.Task;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.config.Config.ConfigBuilder;
import zju.cst.aces.dto.*;
import zju.cst.aces.parser.ProjectParser;
import zju.cst.aces.runner.AbstractRunner;
import zju.cst.aces.coverage.CoverageSummaryReporter;
import zju.cst.aces.status.StatusOutput;
import zju.cst.aces.status.StatusTicker;
import zju.cst.aces.util.testpilot.JavadocCodeExampleCheck;
import zju.cst.aces.util.testpilot.SnippetAnalyzer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.DefaultLoaderRepository;

import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;

import org.apache.maven.project.DefaultProjectBuilder;
import org.apache.maven.project.DefaultProjectBuildingRequest;

import org.apache.maven.project.ProjectBuildingException;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;

import org.apache.maven.project.MavenProject;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionRequest;

import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import zju.cst.aces.api.Project;
import zju.cst.aces.api.impl.GradleProjectImpl;
import zju.cst.aces.api.impl.BuildToolDetector;
import zju.cst.aces.api.impl.ProjectImpl;
import zju.cst.aces.api.impl.RunnerImpl;
import zju.cst.aces.api.phase.step.TestGeneration;
import zju.cst.aces.runner.AbstractRunner;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.FileReader;

import org.apache.commons.cli.*;
// import org.apache.commons.cli.Option;
// import org.apache.commons.cli.OptionBuilder;
// import org.apache.commons.cli.Options;
// import org.apache.commons.cli.DefaultParser;
// import org.apache.commons.cli.CommandLineParser;

public class ChatUniTest {

    private static String pomPath;
    private static String depPath;
    private static String phase;
    private static String cname;
    private static Path outputPath;

    private static String llm;
    private static String url;
    private static String apiKey;

    private static boolean multiThread;
    private static boolean reportCoverage;
    private static boolean resourceProfile;
    private static boolean merge;
    private static boolean helpRequested;
    private static String coverageTestsPath;
    private static String resumePath;
    private static boolean resumeMode;
    private static BuildToolDetector.BuildTool buildTool;

    private static int maxThreads;
    private static int llmThreads;
    private static int compileThreads;
    private static int runThreads;
    private static int coverageThreads;

    private static int timeout;
    
    private static long startTime;


    private static File pomFile;
    private static File baseDir;

    private static int maxTokens;
    private static int maxRounds;

    public void start(String[] args) {
        boolean statusOnly = !isHelpArgument(args);
        if (statusOnly) {
            StatusOutput.installStatusOnly();
        }
        ChatUniTest chatunitest = new ChatUniTest();
        chatunitest.parseCommandLine(args);
        if (chatunitest.isHelpRequested()) {
            if (statusOnly) {
                StatusOutput.restore();
            }
            return;
        }

        try {
            if (chatunitest.isCoverageOnly()) {
                chatunitest.runCoverageOnly();
            } else {
                Task task = chatunitest.buildTask();
                if (task != null) {
                    try (StatusTicker ticker = StatusTicker.generation(task.getConfig()).start()) {
                        task.startProjectTask();
                    }
                }
            }
        } catch (Throwable t) {
            reportFatal(t);
            chatunitest.done();
            System.exit(-1);
        }

        chatunitest.done();
        if (statusOnly) {
            StatusOutput.restore();
        }
        System.exit(0);
        
    }

    private void done() {
        try {
            File file = new File(outputPath.toAbsolutePath().toString() + "/done.txt");
            
            file.createNewFile();
        } catch (IOException e) {
            System.err.println("Done file generation fails: " + e.getMessage());
        }
    }

    private Task buildTask() {
        Task task;
        try {
            Project project;
            if (buildTool == BuildToolDetector.BuildTool.GRADLE) {
                project = new GradleProjectImpl(baseDir, depPath);
            } else {
                MavenXpp3Reader reader = new MavenXpp3Reader();
                Model model = reader.read(new java.io.FileReader(pomFile));
                model.setPomFile(pomFile);
                project = new ProjectImpl(model, baseDir, depPath);
            }
            
            if (resumeMode && !Files.isDirectory(Paths.get(resumePath))) {
                System.out.println("Resume failed: directory does not exist: " + resumePath);
                return null;
            }

            String targetClass = normalizeTargetClass(cname);
            Config config = buildConfig(project, targetClass, outputPath, !resumeMode, reportCoverage);

            config.print();

            // Parser
            ProjectParser prjParser = new ProjectParser(config);

            if (targetClass.isEmpty()) {
                prjParser.parse();

            } else { 
                prjParser.parseClass(targetClass);
            }


            // Tasks
            task = new Task(config, new RunnerImpl(config, timeout, startTime));                    
        } catch (Exception exp) {
            
            System.out.println("Task building process failed.  Reason: " + exp.getMessage());
            return null;
        }

        return task;
    }

    private void runCoverageOnly() throws Exception {
        Project project;
        if (buildTool == BuildToolDetector.BuildTool.GRADLE) {
            project = new GradleProjectImpl(baseDir, depPath);
        } else {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(new java.io.FileReader(pomFile));
            model.setPomFile(pomFile);
            project = new ProjectImpl(model, baseDir, depPath);
        }
        String targetClass = normalizeTargetClass(cname);
        Path coverageTestsOutput = Paths.get(coverageTestsPath);
        Config config = buildConfig(project, targetClass, coverageTestsOutput,
                coverageOnlyTmpOutput(coverageTestsOutput), false, true);
        config.setCoverageOnlyMode(true);

        config.print();
        ProjectParser prjParser = new ProjectParser(config);
        if (targetClass.isEmpty()) {
            prjParser.parse();
        } else {
            prjParser.parseClass(targetClass);
        }

        try (StatusTicker ticker = StatusTicker.coverage(config).start()) {
            new CoverageSummaryReporter(config).report();
        }
    }

    private Config buildConfig(Project project,
                               String targetClass,
                               Path testOutput,
                               boolean isolateTestOutputByRun,
                               boolean reportCoverageEnabled) {
        return buildConfig(project, targetClass, testOutput,
                Paths.get(System.getProperty("java.io.tmpdir"), phase + "/chatunitest-info"),
                isolateTestOutputByRun, reportCoverageEnabled);
    }

    private Config buildConfig(Project project,
                               String targetClass,
                               Path testOutput,
                               Path tmpOutput,
                               boolean isolateTestOutputByRun,
                               boolean reportCoverageEnabled) {
        ConfigBuilder configBuilder = new Config.ConfigBuilder(project);
        return configBuilder.maxThreads(maxThreads).model(llm)
                .llmConcurrency(llmThreads)
                .compileConcurrency(compileThreads)
                .runConcurrency(runThreads)
                .coverageConcurrency(coverageThreads)
                .resourceProfileEnabled(resourceProfile)
                .url(url)
                .apiKey(apiKey)
                .enableMultithreading(multiThread).phaseType(phase).CounterExamplePath(Paths.get(outputPath + "/smartut-tests"))
                .resumeMode(resumeMode)
                .enableMerge(merge)
                .reportCoverage(reportCoverageEnabled)
                .testOutput(testOutput)
                .isolateTestOutputByRun(isolateTestOutputByRun)
                .cname(targetClass)
                .statusOnlyOutput(true)
                .maxRounds(maxRounds)
                .maxPromptTokens(maxTokens)
                .tmpOutput(tmpOutput).build();
    }

    private Path coverageOnlyTmpOutput(Path coverageTestsOutput) {
        return coverageTestsOutput.resolve("chatunitest-info");
    }

    private boolean isCoverageOnly() {
        return coverageTestsPath != null && !coverageTestsPath.trim().isEmpty();
    }
    
    public void parseCommandLine(String[] args) {
        helpRequested = false;
        Options options = getCommandLineOptions();

        // create the parser
        CommandLineParser parser = new DefaultParser();
        try {
            // parse the command line arguments
            CommandLine line = parser.parse(options, args);
            if (line.hasOption("help") || line.hasOption("h")) {
                helpRequested = true;
                printHelp(new PrintWriter(System.out, true));
                return;
            }

            File projectDir = null;
            if (line.hasOption("project")) {
                projectDir = new File(line.getOptionValue("project"));
            }

            boolean explicitPom = line.hasOption("pom");
            if (explicitPom) {
                pomPath = line.getOptionValue("pom");
                buildTool = BuildToolDetector.BuildTool.MAVEN;
            } else if (projectDir != null) {
                buildTool = BuildToolDetector.detect(projectDir);
                if (buildTool == BuildToolDetector.BuildTool.MAVEN) {
                    pomPath = new File(projectDir, "pom.xml").getPath();
                } else if (buildTool != BuildToolDetector.BuildTool.GRADLE) {
                    System.out.println("No build file found in project directory: " + projectDir
                            + " (expected pom.xml or build.gradle/settings.gradle).");
                    return;
                }
            } else {
                return;
            }

            if (line.hasOption("lib")) {
                depPath = line.getOptionValue("lib");
            } else if (projectDir != null) {
                depPath = resolveProjectDependencyPath(projectDir);
            } else {
                return;
            }

            if (buildTool == BuildToolDetector.BuildTool.GRADLE) {
                baseDir = projectDir;
            } else {
                pomFile = new File(pomPath);
                baseDir = pomFile.getParentFile();
                if (projectDir != null && !explicitPom) {
                    compileProjectIfNeeded(projectDir, depPath);
                }
            }

            if (line.hasOption("phase")) {
                phase = line.getOptionValue("phase");

            } else {
                phase = "DEFAULT";
            }

            if (line.hasOption("output")) {
                // outputPath = Paths.get(line.getOptionValue("output")).resolve(phase);
                outputPath = Paths.get(line.getOptionValue("output"));
            } else {
                outputPath = Paths.get(baseDir + "/" + phase);
            }

            if (line.hasOption("llm")) {
                llm = line.getOptionValue("llm");
            } else {
                llm = "Meta-Llama-3.1-70B-Instruct";
            }

            if (line.hasOption("url")) {
                url = line.getOptionValue("url");
            } else {
                url = "http://localhost:8000/v1/chat/completions";
            }

            if (line.hasOption("api_key")) {
                apiKey = line.getOptionValue("api_key");
            } else if (line.hasOption("api-key")) {
                apiKey = line.getOptionValue("api-key");
            } else {
                apiKey = resolveEnvApiKey(System.getenv("OPENAI_API_KEY"));
            }

            if (line.hasOption("class")) {
                cname = normalizeTargetClass(line.getOptionValue("class"));

            } else {
                cname = "";
            }

            if (line.hasOption("multithread")) {
                if (line.getOptionValue("multithread").equals("true")) {
                    multiThread = true;
                } else {
                    multiThread = false;
                }
            }

            if (line.hasOption("maxthreads")) {
                int tmpThreads = Integer.parseInt(line.getOptionValue("maxthreads"));
                int defaultThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
                maxThreads = tmpThreads >= Runtime.getRuntime().availableProcessors() ? defaultThreads : Math.max(1, tmpThreads);
            } else {
                maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
            }

            llmThreads = intOption(line, "llm_threads", 0);
            compileThreads = intOption(line, "compile_threads", 0);
            runThreads = intOption(line, "run_threads", 0);
            coverageThreads = intOption(line, "coverage_threads", 0);

            if (line.hasOption("timeout")) {
                timeout = Integer.parseInt(line.getOptionValue("timeout"));
                startTime = System.currentTimeMillis();
            } else {
                timeout = -1;
                startTime = -1;
            }

            if (line.hasOption("tokens")) {
                maxTokens = Integer.parseInt(line.getOptionValue("tokens"));
            } else {
                maxTokens = 2600;
            }

            if (line.hasOption("rounds")) {
                maxRounds = Integer.parseInt(line.getOptionValue("rounds"));
            } else { 
                maxRounds = 5;
            }

            if (line.hasOption("report_coverage")) {
                reportCoverage = Boolean.parseBoolean(line.getOptionValue("report_coverage"));
            } else if (line.hasOption("report-coverage")) {
                reportCoverage = Boolean.parseBoolean(line.getOptionValue("report-coverage"));
            } else {
                reportCoverage = true;
            }

            if (line.hasOption("resource_profile")) {
                resourceProfile = Boolean.parseBoolean(line.getOptionValue("resource_profile"));
            } else if (line.hasOption("resource-profile")) {
                resourceProfile = Boolean.parseBoolean(line.getOptionValue("resource-profile"));
            } else {
                resourceProfile = false;
            }

            if (line.hasOption("merge")) {
                merge = Boolean.parseBoolean(line.getOptionValue("merge"));
            } else {
                merge = false;
            }

            if (line.hasOption("coverage_tests")) {
                coverageTestsPath = line.getOptionValue("coverage_tests");
            } else if (line.hasOption("coverage-tests")) {
                coverageTestsPath = line.getOptionValue("coverage-tests");
            } else {
                coverageTestsPath = null;
            }

            if (line.hasOption("resume")) {
                resumePath = line.getOptionValue("resume");
                resumeMode = resumePath != null && !resumePath.trim().isEmpty();
            } else {
                resumePath = null;
                resumeMode = false;
            }

            if (resumeMode) {
                outputPath = Paths.get(resumePath);
            }

        } catch (Exception exp) {
            
            System.out.println("Parsing failed.  Reason: " + exp.getMessage());
            return;
        }
    }

    private int intOption(CommandLine line, String option, int defaultValue) {
        if (line.hasOption(option)) {
            return Integer.parseInt(line.getOptionValue(option));
        }
        return defaultValue;
    }

    private String resolveProjectDependencyPath(File projectDir) {
        File plural = new File(projectDir, "target/dependencies");
        if (plural.exists() && plural.isDirectory()) {
            return plural.getPath();
        }
        return new File(projectDir, "target/dependency").getPath();
    }

    private void compileProjectIfNeeded(File projectDir, String dependencyPath) {
        File effectiveProjectDir = projectDir.getAbsoluteFile();
        if (isPomPackaging(effectiveProjectDir) || isCompiled(effectiveProjectDir)) {
            return;
        }

        List<String> command = buildMavenCompileCommand(dependencyPath);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(effectiveProjectDir)
                    .redirectOutput(discardRedirect())
                    .redirectError(discardRedirect())
                    .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Auto compilation failed for " + effectiveProjectDir
                        + " with exit code " + exitCode);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Maven for " + effectiveProjectDir + ": "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while compiling " + effectiveProjectDir, e);
        }
    }

    private boolean isCompiled(File projectDir) {
        return new File(projectDir, "target/classes").isDirectory();
    }

    private ProcessBuilder.Redirect discardRedirect() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        File nullDevice = osName.contains("win") ? new File("NUL") : new File("/dev/null");
        return ProcessBuilder.Redirect.to(nullDevice);
    }

    private boolean isPomPackaging(File projectDir) {
        File pom = new File(projectDir, "pom.xml");
        if (!pom.isFile()) {
            return false;
        }
        try (FileReader reader = new FileReader(pom)) {
            Model model = new MavenXpp3Reader().read(reader);
            return "pom".equals(model.getPackaging());
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> buildMavenCompileCommand(String dependencyPath) {
        List<String> command = new ArrayList<>();
        command.add(resolveMavenExecutable());
        command.add("-q");
        command.add("-DskipTests");
        command.add("test-compile");
        command.add("dependency:copy-dependencies");
        if (dependencyPath != null && !dependencyPath.trim().isEmpty()) {
            command.add("-DoutputDirectory=" + new File(dependencyPath).getAbsolutePath());
        }
        return command;
    }

    private String resolveMavenExecutable() {
        String configured = System.getProperty("chatunitest.maven.executable");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
    }

    public Options getCommandLineOptions() {
        Options options = new Options();

        Option help = new Option("h", "help", false,
        "Print this help message and exit.");

        Option project = new Option(null, "project", true,
        "target Maven project directory for test generation.");

        Option targetPom = new Option(null, "pom", true,
        "target pom file for test generation.");

        Option lib = new Option(null, "lib", true,
        "dependency library paths for generating tests cases.");

        Option phase = new Option(null, "phase", true,
        "Phase selection, default is chatunitest");

        Option output = new Option(null, "output", true,
        "Output path, default is the project path");

        Option model = new Option(null, "llm", true, 
        "Name of the large language model to use.");
        
        Option url = new Option(null, "url", true, 
        "URL serving the LLM.");

        Option apiKey = new Option(null, "api_key", true,
        "API key used for LLM requests. If omitted, falls back to the OPENAI_API_KEY environment variable. Default is NO_API.");

        Option apiKeyDash = new Option(null, "api-key", true,
        "API key used for LLM requests. If omitted, falls back to the OPENAI_API_KEY environment variable. Default is NO_API.");

        Option multithread = new Option(null, "multithread", true,
        "Multi Threading option.");

        Option cname = new Option(null, "class", true, 
        "Target class. If omitted, every class under src/main/java is targeted.");

        Option maxthreads = new Option(null, "maxthreads", true,
        "Number of processor for multi threading. Default value is (#processor - 2)");

        Option resourceProfile = new Option(null, "resource_profile", true,
        "Enable resource limiters and timing profiler. Default is false.");

        Option resourceProfileDash = new Option(null, "resource-profile", true,
        "Enable resource limiters and timing profiler. Default is false.");

        Option llmThreads = new Option(null, "llm_threads", true,
        "Maximum concurrent LLM requests when resource_profile is true. Default is min(4, maxthreads).");

        Option compileThreads = new Option(null, "compile_threads", true,
        "Maximum concurrent compilation validations when resource_profile is true. Default is derived from CPU count.");

        Option runThreads = new Option(null, "run_threads", true,
        "Maximum concurrent unit test executions when resource_profile is true. Default is derived from CPU count.");

        Option coverageThreads = new Option(null, "coverage_threads", true,
        "Maximum concurrent coverage analyses when resource_profile is true. Default is derived from CPU count.");

        Option timeout = new Option(null, "timeout", true,
        "Time budget for generating testing.");

        Option maxToken = new Option(null, "tokens", true,
        "Number of max tokens");

        Option maxRound = new Option(null, "rounds", true,
        "Number of max rounds");

        Option merge = new Option(null, "merge", true,
        "Whether to export merged suite classes under the test_merged directory. Default is false.");

        Option reportCoverage = new Option(null, "report_coverage", true,
        "Whether to report total coverage after generation. Default is true.");

        Option reportCoverageDash = new Option(null, "report-coverage", true,
        "Whether to report total coverage after generation. Default is true.");

        Option coverageTests = new Option(null, "coverage_tests", true,
        "Existing generated unit test source directory. If set, skip generation and report coverage only.");

        Option coverageTestsDash = new Option(null, "coverage-tests", true,
        "Existing generated unit test source directory. If set, skip generation and report coverage only.");

        Option resume = new Option(null, "resume", true,
        "Resume a previous run: path to its run directory. Skips methods already attempted and writes into the same directory.");

        options.addOption(help);
        options.addOption(project);
        options.addOption(targetPom);
        options.addOption(lib);
        options.addOption(phase);
        options.addOption(output);
        options.addOption(model);
        options.addOption(url);
        options.addOption(apiKey);
        options.addOption(apiKeyDash);
        options.addOption(cname);
        options.addOption(multithread);
        options.addOption(maxthreads);
        options.addOption(resourceProfile);
        options.addOption(resourceProfileDash);
        options.addOption(llmThreads);
        options.addOption(compileThreads);
        options.addOption(runThreads);
        options.addOption(coverageThreads);
        options.addOption(timeout);
        options.addOption(maxToken);
        options.addOption(maxRound);
        options.addOption(merge);
        options.addOption(reportCoverage);
        options.addOption(reportCoverageDash);
        options.addOption(coverageTests);
        options.addOption(coverageTestsDash);
        options.addOption(resume);



        return options;
    }

    public void printHelp(PrintWriter writer) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setWidth(120);
        writer.println("Usage:");
        writer.println("  java -jar scout.jar --project <projectDir> [options]");
        writer.println("  java -jar scout.jar --pom <pomFile> --lib <dependencyDir> [options]");
        writer.println();
        writer.println("Options:");
        formatter.printOptions(writer, 120, getCommandLineOptions(), 2, 4);
        writer.println();
        writer.println("Examples:");
        writer.println("  Generate tests for a project:");
        writer.println("    java -jar scout.jar --project /path/to/project --phase SCOUT --llm code-llama --url http://localhost:8000/v1/chat/completions");
        writer.println();
        writer.println("  Generate tests for one class:");
        writer.println("    java -jar scout.jar --project /path/to/project --class com.example.Target --output /tmp/chatunitest-out");
        writer.println();
        writer.println("  Coverage only with existing generated tests:");
        writer.println("    java -jar scout.jar --project /path/to/project --coverage-tests /path/to/generated-tests --class com.example.Target");
        writer.flush();
    }

    private boolean isHelpRequested() {
        return helpRequested;
    }

    private static String normalizeTargetClass(String targetClass) {
        return targetClass == null ? "" : targetClass.trim();
    }

    static String resolveEnvApiKey(String rawEnvValue) {
        return (rawEnvValue == null || rawEnvValue.trim().isEmpty())
                ? Config.NO_API
                : rawEnvValue.trim();
    }

    private static boolean isHelpArgument(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        boolean statusOnly = !isHelpArgument(args);
        if (statusOnly) {
            StatusOutput.installStatusOnly();
        }
        ChatUniTest chatunitest = new ChatUniTest();
        chatunitest.parseCommandLine(args);
        if (chatunitest.isHelpRequested()) {
            if (statusOnly) {
                StatusOutput.restore();
            }
            return;
        }

        try {
            if (chatunitest.isCoverageOnly()) {
                chatunitest.runCoverageOnly();
            } else {
                Task task = chatunitest.buildTask();

                if (task != null) {
                    try (StatusTicker ticker = StatusTicker.generation(task.getConfig()).start()) {
                        task.startProjectTask();
                    }
                }
            }
        } catch (Throwable t) {
            reportFatal(t);
            chatunitest.done();
            System.exit(-1);

        }


        chatunitest.done();
        if (statusOnly) {
            StatusOutput.restore();
        }
        System.exit(0);
    }

    private static void reportFatal(Throwable failure) {
        String type = failure == null ? "UnknownError" : failure.getClass().getSimpleName();
        String message = failure == null ? "unknown failure" : failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "no error message";
        }
        StatusOutput.deferDiagnostic("Fatal crash on main ChatUniTest process: " + type + ": " + message);
        StatusOutput.restore();
    }
}
