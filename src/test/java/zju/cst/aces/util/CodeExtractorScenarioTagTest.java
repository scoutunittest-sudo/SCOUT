package zju.cst.aces.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeExtractorScenarioTagTest {
    @Test
    void extractsTaggedTestMethodWithScenarioComment() {
        String response = "// SCOUT-SCENARIO: scenario-2\n" +
                "@Test\n" +
                "void coversLargeAmountPath() {\n" +
                "    assertEquals(1, sample.getValue());\n" +
                "}\n";

        String extracted = new CodeExtractor(response).getExtractedCode();

        assertTrue(extracted.contains("// SCOUT-SCENARIO: scenario-2"));
        assertTrue(extracted.contains("void coversLargeAmountPath()"));
    }
}
