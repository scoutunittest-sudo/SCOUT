package zju.cst.aces.scout.prompt;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutCachedTestFormatterTest {
    @Test
    void extractsConstructorAndSetupStatementsWithoutAssertionsOrActCalls() {
        String cachedTest = "class SampleTest {\n" +
                "  @org.junit.jupiter.api.Test\n" +
                "  void cached() {\n" +
                "    Dependency dependency = org.mockito.Mockito.mock(Dependency.class);\n" +
                "    org.mockito.Mockito.when(dependency.load()).thenReturn(\"ok\");\n" +
                "    Sample sample = new Sample(\"id\", dependency);\n" +
                "    int value = sample.getValue();\n" +
                "    org.junit.jupiter.api.Assertions.assertEquals(1, value);\n" +
                "  }\n" +
                "}\n";

        String formatted = new ScoutCachedTestFormatter().format(Collections.singletonList(cachedTest));

        assertTrue(formatted.contains("Dependency dependency = org.mockito.Mockito.mock(Dependency.class);"));
        assertTrue(formatted.contains("org.mockito.Mockito.when(dependency.load()).thenReturn(\"ok\");"));
        assertTrue(formatted.contains("Sample sample = new Sample(\"id\", dependency);"));
        assertFalse(formatted.contains("sample.getValue()"));
        assertFalse(formatted.contains("assertEquals"));
    }

    @Test
    void extractsSetupStatementsFromLifecycleMethodsAndPartialMethods() {
        String cachedClass = "class SampleTest {\n" +
                "  private Dependency dependency = org.mockito.Mockito.mock(Dependency.class);\n" +
                "  private Sample sample;\n" +
                "  @org.junit.jupiter.api.BeforeEach\n" +
                "  void setUp() {\n" +
                "    sample = new Sample(dependency);\n" +
                "  }\n" +
                "  @org.junit.jupiter.api.Test\n" +
                "  void cached() {\n" +
                "    int value = sample.getValue();\n" +
                "    org.junit.jupiter.api.Assertions.assertEquals(1, value);\n" +
                "  }\n" +
                "}\n";
        String partialMethod = "@org.junit.jupiter.api.Test\n" +
                "void cachedPartial() {\n" +
                "  Service service = org.mockito.Mockito.mock(Service.class);\n" +
                "  Sample sample = Sample.builder().service(service).build();\n" +
                "  sample.getValue();\n" +
                "}\n";

        String formatted = new ScoutCachedTestFormatter().format(java.util.Arrays.asList(cachedClass, partialMethod));

        assertTrue(formatted.contains("Dependency dependency = org.mockito.Mockito.mock(Dependency.class);"));
        assertTrue(formatted.contains("sample = new Sample(dependency);"));
        assertTrue(formatted.contains("Service service = org.mockito.Mockito.mock(Service.class);"));
        assertTrue(formatted.contains("Sample sample = Sample.builder().service(service).build();"));
        assertFalse(formatted.contains("sample.getValue()"));
        assertFalse(formatted.contains("assertEquals"));
    }

    @Test
    void fallsBackToRawCachedTestWhenNoSetupSnippetCanBeExtracted() {
        String cachedTest = "class SampleTest {\n" +
                "  @org.junit.jupiter.api.Test\n" +
                "  void cached() {\n" +
                "    int value = sample.getValue();\n" +
                "    org.junit.jupiter.api.Assertions.assertEquals(1, value);\n" +
                "  }\n" +
                "}\n";

        String formatted = new ScoutCachedTestFormatter().format(Collections.singletonList(cachedTest));

        assertTrue(formatted.contains("class SampleTest"));
        assertTrue(formatted.contains("void cached()"));
        assertTrue(formatted.contains("sample.getValue()"));
        assertTrue(formatted.contains("assertEquals"));
    }
}
