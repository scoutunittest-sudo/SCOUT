package zju.cst.aces.api.phase.solution;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.scout.generation.ScoutTestGeneration;
import zju.cst.aces.scout.state.ScoutStateStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class SCOUT extends PhaseImpl {
    private static final DateTimeFormatter RUN_DIRECTORY_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private final ScoutGenerationFactory generationFactory;
    private final Path scoutRunBasePath;

    public SCOUT(Config config) {
        this(config, new ScoutGenerationFactory() {
            @Override
            public ScoutGenerationStep create(Config config, ScoutStateStore store) {
                return new ScoutTestGenerationStep(new ScoutTestGeneration(config, store));
            }
        });
    }

    SCOUT(Config config, ScoutGenerationFactory generationFactory) {
        super(config);
        this.generationFactory = generationFactory;
        this.scoutRunBasePath = resolveScoutRunBasePath();
    }

    @Override
    public void generateTest(PromptConstructorImpl pc) {
        createScoutGenerationStep().execute(pc);
    }

    @Override
    public void repairTest(PromptConstructorImpl pc) {
        createScoutGenerationStep().execute(pc);
    }

    @Override
    public boolean validateTest(PromptConstructorImpl pc) {
        if (!validateGeneratedTest(pc)) {
            return false;
        }
        clearValidationError(pc);
        return createScoutGenerationStep().recordValidatedTest(pc);
    }

    protected boolean validateGeneratedTest(PromptConstructorImpl pc) {
        return super.validateTest(pc);
    }

    private void clearValidationError(PromptConstructorImpl pc) {
        if (pc != null && pc.getPromptInfo() != null) {
            pc.getPromptInfo().setErrorMsg(null);
        }
    }

    private ScoutGenerationStep createScoutGenerationStep() {
        return generationFactory.create(config, createScoutStateStore());
    }

    ScoutStateStore createScoutStateStore() {
        return new ScoutStateStore(scoutStateBasePath(), config);
    }

    Path scoutStateBasePath() {
        return scoutRunBasePath;
    }

    private Path configuredTmpOutput() {
        if (config != null && config.getTmpOutput() != null) {
            return config.getTmpOutput();
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "chatunitest-info", "scout");
    }

    private Path resolveScoutRunBasePath() {
        if (config == null) {
            return createScoutRunBasePath(configuredTmpOutput());
        }
        synchronized (config) {
            if (config.getScoutRunOutput() == null) {
                config.setScoutRunOutput(createScoutRunBasePath(configuredTmpOutput()));
            }
            return config.getScoutRunOutput();
        }
    }

    private Path createScoutRunBasePath(Path tmpOutput) {
        Path runsRoot = tmpOutput.resolve("scout-runs").resolve(resolveModelDirectoryName());
        String timestamp = LocalDateTime.now().format(RUN_DIRECTORY_FORMAT);
        for (int i = 0; i < 1000; i++) {
            Path candidate = runsRoot.resolve(i == 0 ? timestamp : timestamp + "-" + i);
            try {
                Files.createDirectories(runsRoot);
                return Files.createDirectory(candidate);
            } catch (IOException e) {
                if (Files.exists(candidate)) {
                    continue;
                }
                throw new IllegalStateException("Failed to create SCOUT run directory: " + candidate, e);
            }
        }
        throw new IllegalStateException("Failed to create unique SCOUT run directory under " + runsRoot);
    }

    private String resolveModelDirectoryName() {
        String rawModelName = null;
        if (config != null) {
            rawModelName = config.getModelName();
            if ((rawModelName == null || rawModelName.trim().isEmpty()) && config.getModel() != null) {
                rawModelName = config.getModel().getModelName();
            }
        }
        return sanitizeDirectorySegment(rawModelName, "unknown-model");
    }

    private String sanitizeDirectorySegment(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String sanitized = value.trim()
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return sanitized.isEmpty() ? fallback : sanitized;
    }

    interface ScoutGenerationFactory {
        ScoutGenerationStep create(Config config, ScoutStateStore store);
    }

    interface ScoutGenerationStep {
        void execute(PromptConstructorImpl pc);

        boolean recordValidatedTest(PromptConstructorImpl pc);
    }

    private static class ScoutTestGenerationStep implements ScoutGenerationStep {
        private final ScoutTestGeneration scoutTestGeneration;

        private ScoutTestGenerationStep(ScoutTestGeneration scoutTestGeneration) {
            this.scoutTestGeneration = scoutTestGeneration;
        }

        @Override
        public void execute(PromptConstructorImpl pc) {
            scoutTestGeneration.execute(pc);
        }

        @Override
        public boolean recordValidatedTest(PromptConstructorImpl pc) {
            return scoutTestGeneration.recordValidatedTest(pc);
        }
    }
}
