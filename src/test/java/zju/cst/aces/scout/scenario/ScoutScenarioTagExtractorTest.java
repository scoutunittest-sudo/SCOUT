package zju.cst.aces.scout.scenario;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoutScenarioTagExtractorTest {
    @Test
    void extractsScenarioTagsFromTestMethods() {
        String code = "class SampleTest {\n" +
                "  // SCOUT-SCENARIO: scenario-2\n" +
                "  @org.junit.jupiter.api.Test\n" +
                "  void coversLargeAmountPath() {}\n" +
                "\n" +
                "  // SCOUT-SCENARIO: scenario-3\n" +
                "  @Test\n" +
                "  public void coversFallbackPath() {}\n" +
                "}\n";

        List<ScoutScenarioTag> tags = new ScoutScenarioTagExtractor().extract(code);

        assertEquals(2, tags.size());
        assertEquals("scenario-2", tags.get(0).getScenarioId());
        assertEquals("coversLargeAmountPath", tags.get(0).getMethodName());
        assertEquals("scenario-3", tags.get(1).getScenarioId());
        assertEquals("coversFallbackPath", tags.get(1).getMethodName());
    }
}
