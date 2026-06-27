package zju.cst.aces.api.phase.solution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.dto.TestMessage;
import zju.cst.aces.scout.state.ScoutProjectState;
import zju.cst.aces.scout.state.ScoutStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SCOUTRunDirectoryTest {
    @TempDir
    Path tmpOutput;

    @Test
    void usesOneTimestampedStateDirectoryPerScoutInstance() throws Exception {
        Config config = new Config();
        config.setTmpOutput(tmpOutput);

        SCOUT scout = new SCOUT(config, new NoOpScoutGenerationFactory());

        Path first = scout.scoutStateBasePath();
        Path second = scout.scoutStateBasePath();

        assertEquals(first, second);
        assertEquals(tmpOutput.resolve("scout-runs").resolve("unknown-model"), first.getParent());
        assertTrue(first.getFileName().toString().matches("\\d{8}-\\d{6}-\\d{3}(?:-\\d+)?"));

        ScoutStateStore store = scout.createScoutStateStore();
        ScoutProjectState state = new ScoutProjectState();
        state.setProjectSummary("fresh run");
        store.saveProjectState(state);

        assertTrue(Files.exists(first.resolve("scout-state").resolve("project.json")));
    }

    @Test
    void stateDirectoryIncludesSanitizedModelNameWhenConfigured() {
        Config config = new Config();
        config.setTmpOutput(tmpOutput);
        config.setModelName("codeqwen:v1.5-chat");

        SCOUT scout = new SCOUT(config, new NoOpScoutGenerationFactory());

        assertEquals(tmpOutput.resolve("scout-runs").resolve("codeqwen-v1.5-chat"),
                scout.scoutStateBasePath().getParent());
    }

    @Test
    void reusesStateDirectoryForDifferentScoutInstancesInSameConfig() {
        Config config = new Config();
        config.setTmpOutput(tmpOutput);

        SCOUT first = new SCOUT(config, new NoOpScoutGenerationFactory());
        SCOUT second = new SCOUT(config, new NoOpScoutGenerationFactory());

        assertEquals(first.scoutStateBasePath(), second.scoutStateBasePath());
    }

    @Test
    void createsDifferentStateDirectoriesForDifferentConfigs() {
        Config firstConfig = new Config();
        firstConfig.setTmpOutput(tmpOutput);
        Config secondConfig = new Config();
        secondConfig.setTmpOutput(tmpOutput);

        SCOUT first = new SCOUT(firstConfig, new NoOpScoutGenerationFactory());
        SCOUT second = new SCOUT(secondConfig, new NoOpScoutGenerationFactory());

        assertNotEquals(first.scoutStateBasePath(), second.scoutStateBasePath());
    }

    @Test
    void clearsStaleValidationErrorAfterGeneratedTestPassesValidation() {
        Config config = new Config();
        config.setTmpOutput(tmpOutput);
        PromptInfo promptInfo = new PromptInfo(false, "demo.Sample", "getValue", "getValue()");
        promptInfo.setUnitTest("class Sample0_Test {}");

        TestMessage staleError = new TestMessage();
        staleError.setErrorType(TestMessage.ErrorType.COMPILE_ERROR);
        staleError.setErrorMessage(Collections.singletonList("old compile error"));
        promptInfo.setErrorMsg(staleError);

        PromptConstructorImpl pc = new PromptConstructorImpl(config);
        pc.setPromptInfo(promptInfo);
        pc.setFullTestName("demo.Sample0_Test");

        LegacyValidationFactory factory = new LegacyValidationFactory();
        ValidationControlledScout scout = new ValidationControlledScout(config, factory, true);

        assertTrue(scout.validateTest(pc));

        assertNull(promptInfo.getErrorMsg());
        assertTrue(scout.validationCalled);
        assertTrue(factory.recordValidatedTestCalled);
    }

    @Test
    void validateTestUsesGeneratedValidationBeforeRecordingScoutCoverage() {
        Config config = new Config();
        config.setTmpOutput(tmpOutput);
        PromptInfo promptInfo = new PromptInfo(false, "demo.Sample", "getValue", "getValue()");
        promptInfo.setUnitTest("class Sample0_Test {}");

        PromptConstructorImpl pc = new PromptConstructorImpl(config);
        pc.setPromptInfo(promptInfo);
        pc.setFullTestName("demo.Sample0_Test");

        LegacyValidationFactory factory = new LegacyValidationFactory();
        ValidationControlledScout scout = new ValidationControlledScout(config, factory, true);

        assertTrue(scout.validateTest(pc));
        assertTrue(scout.validationCalled);
        assertTrue(factory.recordValidatedTestCalled);
    }

    @Test
    void validateTestDoesNotRecordScoutCoverageWhenGeneratedValidationFails() {
        Config config = new Config();
        config.setTmpOutput(tmpOutput);
        PromptInfo promptInfo = new PromptInfo(false, "demo.Sample", "getValue", "getValue()");
        promptInfo.setUnitTest("class Sample0_Test {}");

        PromptConstructorImpl pc = new PromptConstructorImpl(config);
        pc.setPromptInfo(promptInfo);
        pc.setFullTestName("demo.Sample0_Test");

        LegacyValidationFactory factory = new LegacyValidationFactory();
        ValidationControlledScout scout = new ValidationControlledScout(config, factory, false);

        assertFalse(scout.validateTest(pc));
        assertTrue(scout.validationCalled);
        assertFalse(factory.recordValidatedTestCalled);
    }

    private static class NoOpScoutGenerationFactory implements SCOUT.ScoutGenerationFactory {
        @Override
        public SCOUT.ScoutGenerationStep create(Config config, ScoutStateStore store) {
            return new SCOUT.ScoutGenerationStep() {
                @Override
                public void execute(zju.cst.aces.api.impl.PromptConstructorImpl pc) {
                }

                @Override
                public boolean recordValidatedTest(zju.cst.aces.api.impl.PromptConstructorImpl pc) {
                    return false;
                }
            };
        }
    }

    private static class LegacyValidationFactory implements SCOUT.ScoutGenerationFactory {
        private boolean recordValidatedTestCalled;

        @Override
        public SCOUT.ScoutGenerationStep create(Config config, ScoutStateStore store) {
            return new SCOUT.ScoutGenerationStep() {
                @Override
                public void execute(zju.cst.aces.api.impl.PromptConstructorImpl pc) {
                }

                @Override
                public boolean recordValidatedTest(zju.cst.aces.api.impl.PromptConstructorImpl pc) {
                    recordValidatedTestCalled = true;
                    return true;
                }
            };
        }
    }

    private static class ValidationControlledScout extends SCOUT {
        private final boolean validationResult;
        private boolean validationCalled;

        private ValidationControlledScout(Config config,
                                          SCOUT.ScoutGenerationFactory generationFactory,
                                          boolean validationResult) {
            super(config, generationFactory);
            this.validationResult = validationResult;
        }

        @Override
        protected boolean validateGeneratedTest(PromptConstructorImpl pc) {
            validationCalled = true;
            return validationResult;
        }
    }

}
