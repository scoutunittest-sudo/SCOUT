package zju.cst.aces.scout.prompt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutDescriptionPromptTemplateTest {

    @Test
    void projectAndClassSummaryPromptsAskForCompactOutput() throws IOException {
        String project = resource("prompt/scout_export_project.ftl");
        String clazz = resource("prompt/scout_export_class.ftl");
        String aspect = resource("prompt/scout_export_class_aspect.ftl");

        assertTrue(project.contains("At most 3 bullets"));
        assertTrue(clazz.contains("At most 4 bullets"));
        assertTrue(clazz.contains("class-wide role"));
        assertTrue(clazz.contains("facts that apply across methods"));
        assertTrue(clazz.contains("Do not summarize each method"));
        assertTrue(aspect.contains("At most 5 bullets"));
        assertTrue(project.contains("Do not repeat README text"));
        assertTrue(clazz.contains("Do not explain Java syntax"));
    }

    @Test
    void methodAndScenarioPromptsStayUncappedButFocused() throws IOException {
        String method = resource("prompt/scout_export_method.ftl");
        String scenario = resource("prompt/scout_export_method_scenario.ftl");

        assertTrue(method.contains("Include as many details as the method complexity requires"));
        assertTrue(method.contains("Class-wide context"));
        assertTrue(scenario.contains("List all distinct concrete test scenarios"));
        assertTrue(scenario.contains("condition -> expected behavior"));
        assertFalse(method.contains("Project summary:"));
        assertFalse(method.contains("At most"));
        assertFalse(method.contains("No more than"));
        assertFalse(scenario.contains("At most"));
        assertFalse(scenario.contains("No more than"));
    }

    private String resource(String name) throws IOException {
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
        if (stream == null) {
            throw new IOException("Missing test resource: " + name);
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
    }
}
