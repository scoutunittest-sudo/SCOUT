package zju.cst.aces.scout.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoutPromptRendererTest {
    @Test
    void unitTestPromptsUseScenarioWithoutProjectClassOrMethodSummaries() {
        ScoutPromptRenderer renderer = new ScoutPromptRenderer(null);

        for (ScoutPromptMode mode : ScoutPromptMode.values()) {
            ScoutPromptContext context = new ScoutPromptContext();
            context.setMode(mode);
            context.getDataModel().put("project_summary", "PROJECT_SUMMARY_SHOULD_NOT_RENDER");
            context.getDataModel().put("class_summary", "CLASS_SUMMARY_SHOULD_NOT_RENDER");
            context.getDataModel().put("method_summary", "METHOD_SUMMARY_SHOULD_NOT_RENDER");
            context.getDataModel().put("scenario", "METHOD_SCENARIO_SHOULD_RENDER");

            String prompt = renderer.renderUser(context);

            assertTrue(prompt.contains("METHOD_SCENARIO_SHOULD_RENDER"), mode.name());
            assertFalse(prompt.contains("PROJECT_SUMMARY_SHOULD_NOT_RENDER"), mode.name());
            assertFalse(prompt.contains("CLASS_SUMMARY_SHOULD_NOT_RENDER"), mode.name());
            assertFalse(prompt.contains("METHOD_SUMMARY_SHOULD_NOT_RENDER"), mode.name());
            assertFalse(prompt.contains("Project summary:"), mode.name());
            assertFalse(prompt.contains("Class summary:"), mode.name());
            assertFalse(prompt.contains("Method summary:"), mode.name());
            assertTrue(prompt.contains("Cached constructor/setup examples:"), mode.name());
            assertFalse(prompt.contains("Cached passing tests:"), mode.name());
            assertTrue(prompt.contains("// SCOUT-SCENARIO: <scenario-id>"), mode.name());
        }
    }

    @Test
    void repairPromptRendersPreviousTestErrorAndCachedSetupExamples() {
        ScoutPromptContext context = new ScoutPromptContext();
        context.setMode(ScoutPromptMode.GUIDE_COVERAGE);
        context.getDataModel().put("scenario", "large amount path");
        context.getDataModel().put("cached_tests", "Sample sample = new Sample(dependency);");
        context.getDataModel().put("uncovered_regions", "if (amount > 10)");
        context.getDataModel().put("unit_test", "class PreviousSampleTest {}");
        context.getDataModel().put("error_message", "cannot find symbol: constructor Sample()");

        String prompt = new ScoutPromptRenderer(null).renderUser(context);

        assertTrue(prompt.contains("Cached constructor/setup examples:"));
        assertTrue(prompt.contains("Sample sample = new Sample(dependency);"));
        assertTrue(prompt.contains("Uncovered regions to target:"));
        assertTrue(prompt.contains("if (amount > 10)"));
        assertTrue(prompt.contains("Previous unit test:"));
        assertTrue(prompt.contains("class PreviousSampleTest {}"));
        assertTrue(prompt.contains("Previous error:"));
        assertTrue(prompt.contains("cannot find symbol: constructor Sample()"));
    }

    @Test
    void coverageRepairPromptsRenderCoveredAndUncoveredScenarioStatus() {
        for (ScoutPromptMode mode : new ScoutPromptMode[]{
                ScoutPromptMode.GUIDE_COVERAGE,
                ScoutPromptMode.GUIDE_COVERAGE_NO_BRANCH,
                ScoutPromptMode.GUIDE_COVERAGE_CALLERS
        }) {
            ScoutPromptContext context = new ScoutPromptContext();
            context.setMode(mode);
            context.getDataModel().put("scenario", "large amount path");
            context.getDataModel().put("scenario_guidance", "Target scenario cards:\n- Scenario: [scenario-2] large amount path");
            context.getDataModel().put("covered_scenarios", "small amount path");
            context.getDataModel().put("uncovered_scenarios", "large amount path");
            context.getDataModel().put("tagged_scenario_attempts",
                    "- scenario-2 via coversLargeAmountPath: attempted, but still uncovered");
            context.getDataModel().put("tagged_scenario_test_examples",
                    "[scenario-2 via coversLargeAmountPath]\n@Test void coversLargeAmountPath() {}");

            String prompt = new ScoutPromptRenderer(null).renderUser(context);

            assertTrue(prompt.contains("Covered scenarios from previous unit test:"), mode.name());
            assertTrue(prompt.contains("small amount path"), mode.name());
            assertTrue(prompt.contains("Uncovered scenarios to repair:"), mode.name());
            assertTrue(prompt.contains("large amount path"), mode.name());
            assertTrue(prompt.contains("Use scenario status and uncovered regions as the primary repair targets"), mode.name());
            assertTrue(prompt.contains("Keep previous working tests unless they block compilation"), mode.name());
            assertTrue(prompt.contains("Prefer adding new test methods for uncovered scenarios"), mode.name());
            assertTrue(prompt.contains("Tagged scenario attempts from previous unit test:"), mode.name());
            assertTrue(prompt.contains("scenario-2 via coversLargeAmountPath"), mode.name());
            assertTrue(prompt.contains("Previous tagged test methods for target scenarios:"), mode.name());
            assertTrue(prompt.contains("@Test void coversLargeAmountPath() {}"), mode.name());
            assertFalse(prompt.contains("First repair the compile/runtime failure"), mode.name());
        }
    }

    @Test
    void errorRepairPromptsPrioritizeCompileRuntimeFailuresOverCoverageTargets() {
        for (ScoutPromptMode mode : new ScoutPromptMode[]{
                ScoutPromptMode.ERROR,
                ScoutPromptMode.ERROR_CONSTR
        }) {
            ScoutPromptContext context = new ScoutPromptContext();
            context.setMode(mode);
            context.getDataModel().put("scenario", "large amount path");
            context.getDataModel().put("unit_test", "class PreviousSampleTest {}");
            context.getDataModel().put("error_message", "Error type: COMPILE_ERROR\ncannot find symbol");

            String prompt = new ScoutPromptRenderer(null).renderUser(context);

            assertTrue(prompt.contains("First repair the compile/runtime failure"), mode.name());
            assertTrue(prompt.contains("Use Previous error and Previous unit test as the primary repair inputs"), mode.name());
            assertTrue(prompt.contains("Do not add coverage-only scenarios until the test compiles and runs"), mode.name());
            assertFalse(prompt.contains("Use scenario status and uncovered regions as the primary repair targets"), mode.name());
        }
    }
}
