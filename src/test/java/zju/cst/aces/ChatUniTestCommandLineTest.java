package zju.cst.aces;

import org.apache.commons.cli.Options;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUniTestCommandLineTest {
    @Test
    void exposesCoverageOnlyExistingTestsOption() {
        Options options = new ChatUniTest().getCommandLineOptions();

        assertTrue(options.hasOption("coverage_tests"));
        assertTrue(options.hasOption("coverage-tests"));
        assertTrue(options.hasOption("merge"));
        assertTrue(options.hasOption("resource_profile"));
        assertTrue(options.hasOption("resource-profile"));
        assertTrue(options.hasOption("llm_threads"));
        assertTrue(options.hasOption("compile_threads"));
        assertTrue(options.hasOption("run_threads"));
        assertTrue(options.hasOption("coverage_threads"));
    }

    @Test
    void exposesHelpOptions() {
        Options options = new ChatUniTest().getCommandLineOptions();

        assertTrue(options.hasOption("help"));
        assertTrue(options.hasOption("h"));
    }

    @Test
    void rendersHelpWithParameterDescriptionsAndExamples() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(output);

        new ChatUniTest().printHelp(writer);
        writer.flush();

        String help = output.toString();
        assertTrue(help.contains("Usage:"));
        assertTrue(help.contains("--project"));
        assertTrue(help.contains("--coverage-tests"));
        assertTrue(help.contains("merge"));
        assertTrue(help.contains("resource_profile"));
        assertTrue(help.contains("llm_threads"));
        assertTrue(help.contains("coverage_threads"));
        assertTrue(help.contains("Default is true"));
        assertTrue(help.contains("Examples:"));
        assertTrue(help.contains("Generate tests"));
        assertTrue(help.contains("Coverage only"));
        assertFalse(help.trim().isEmpty());
    }
}
