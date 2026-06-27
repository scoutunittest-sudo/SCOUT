package zju.cst.aces.scout.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.scout.scenario.ScoutBranchMapping;
import zju.cst.aces.scout.scenario.ScoutScenario;
import zju.cst.aces.scout.scenario.ScoutScenarioTag;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

public class ScoutStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path stateRoot;
    private final Config config;

    public ScoutStateStore(Path tmpOutput) {
        this(tmpOutput, null);
    }

    public ScoutStateStore(Path tmpOutput, Config config) {
        this.stateRoot = tmpOutput.resolve("scout-state");
        this.config = config;
    }

    public ScoutProjectState loadProjectState() throws IOException {
        return loadOrCreate(projectPath(), ScoutProjectState.class, ScoutProjectState::new);
    }

    public void saveProjectState(ScoutProjectState state) throws IOException {
        save(projectPath(), state);
    }

    public ScoutClassState loadClassState(String fullClassName) throws IOException {
        Path path = classPath(fullClassName);
        ScoutClassState state = loadOrCreate(path, ScoutClassState.class, ScoutClassState::new);
        if (state.getFullClassName() == null || state.getFullClassName().isEmpty()) {
            state.setFullClassName(fullClassName);
        }
        if (state.getClassName() == null || state.getClassName().isEmpty()) {
            state.setClassName(simpleClassName(fullClassName));
        }
        save(path, state);
        return state;
    }

    public void saveClassState(ScoutClassState state) throws IOException {
        save(classPath(state.getFullClassName()), state);
    }

    public ScoutMethodState loadMethodState(String fullClassName, String methodSignature) throws IOException {
        Path path = methodPath(fullClassName, methodSignature);
        ScoutMethodState state = loadOrCreate(path, ScoutMethodState.class, ScoutMethodState::new);
        normalizeMethodState(state);
        if (state.getFullClassName() == null || state.getFullClassName().isEmpty()) {
            state.setFullClassName(fullClassName);
        }
        if (state.getMethodSignature() == null || state.getMethodSignature().isEmpty()) {
            state.setMethodSignature(methodSignature);
        }
        save(path, state);
        return state;
    }

    private void normalizeMethodState(ScoutMethodState state) {
        if (state.getUncoveredRegionsByAttempt() == null) {
            state.setUncoveredRegionsByAttempt(new LinkedHashMap<String, List<String>>());
        }
        if (state.getStableUncoveredRegions() == null) {
            state.setStableUncoveredRegions(new ArrayList<String>());
        }
        if (state.getScenarios() == null) {
            state.setScenarios(new ArrayList<ScoutScenario>());
        }
        for (ScoutScenario scenario : state.getScenarios()) {
            normalizeScenario(scenario);
        }
        if (state.getCoveredScenarioIdsByAttempt() == null) {
            state.setCoveredScenarioIdsByAttempt(new LinkedHashMap<String, List<String>>());
        }
        if (state.getTaggedScenarioAttemptsByAttempt() == null) {
            state.setTaggedScenarioAttemptsByAttempt(new LinkedHashMap<String, List<ScoutScenarioTag>>());
        }
        if (state.getUncoveredScenarioIds() == null) {
            state.setUncoveredScenarioIds(new ArrayList<String>());
        }
        if (state.getScenarioGuidance() == null) {
            state.setScenarioGuidance("");
        }
    }

    private void normalizeScenario(ScoutScenario scenario) {
        if (scenario == null) {
            return;
        }
        if (scenario.getId() == null) {
            scenario.setId("");
        }
        if (scenario.getTitle() == null) {
            scenario.setTitle("");
        }
        if (scenario.getDescription() == null) {
            scenario.setDescription("");
        }
        if (scenario.getBranchHints() == null) {
            scenario.setBranchHints(new ArrayList<String>());
        }
        if (scenario.getArgumentHints() == null) {
            scenario.setArgumentHints(new ArrayList<String>());
        }
        if (scenario.getDependencyHints() == null) {
            scenario.setDependencyHints(new ArrayList<String>());
        }
        if (scenario.getRelatedLines() == null) {
            scenario.setRelatedLines(new ArrayList<Integer>());
        }
        if (scenario.getBranchMappings() == null) {
            scenario.setBranchMappings(new ArrayList<ScoutBranchMapping>());
        }
        for (ScoutBranchMapping mapping : scenario.getBranchMappings()) {
            normalizeBranchMapping(mapping);
        }
    }

    private void normalizeBranchMapping(ScoutBranchMapping mapping) {
        if (mapping == null) {
            return;
        }
        if (mapping.getBranchText() == null) {
            mapping.setBranchText("");
        }
        if (mapping.getOutcome() == null) {
            mapping.setOutcome(ScoutBranchMapping.UNKNOWN);
        }
        if (mapping.getParameterDependencies() == null) {
            mapping.setParameterDependencies(new ArrayList<String>());
        }
        if (mapping.getFieldDependencies() == null) {
            mapping.setFieldDependencies(new ArrayList<String>());
        }
        if (mapping.getCallDependencies() == null) {
            mapping.setCallDependencies(new ArrayList<String>());
        }
    }

    public void saveMethodState(ScoutMethodState state) throws IOException {
        save(methodPath(state.getFullClassName(), state.getMethodSignature()), state);
    }

    private <T> T loadOrCreate(Path path, Class<T> type, Supplier<T> defaultSupplier) throws IOException {
        long startNanos = System.nanoTime();
        try {
            if (Files.exists(path)) {
                try {
                    String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    T state = GSON.fromJson(json, type);
                    if (state != null) {
                        return state;
                    }
                } catch (JsonSyntaxException ignored) {
                    // Malformed state files are replaced with defaults below.
                }
            }
            T state = defaultSupplier.get();
            save(path, state);
            return state;
        } finally {
            recordStateTiming(startNanos);
        }
    }

    private void save(Path path, Object state) throws IOException {
        long startNanos = System.nanoTime();
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
        } finally {
            recordStateTiming(startNanos);
        }
    }

    private Path projectPath() {
        return stateRoot.resolve("project.json");
    }

    private Path classPath(String fullClassName) {
        Path primary = stateRoot.resolve("classes").resolve(safe(fullClassName) + ".json");
        if (!Files.exists(primary)) {
            return primary;
        }
        ScoutClassState existing = readExisting(primary, ScoutClassState.class);
        if (existing == null || isEmptyOrEquals(existing.getFullClassName(), fullClassName)) {
            return primary;
        }
        return stateRoot.resolve("classes").resolve(safe(fullClassName) + "__" + shortHash(fullClassName) + ".json");
    }

    private Path methodPath(String fullClassName, String methodSignature) {
        Path primary = stateRoot.resolve("methods")
                .resolve(safe(fullClassName))
                .resolve(safe(methodSignature) + ".json");
        if (!Files.exists(primary)) {
            return primary;
        }
        ScoutMethodState existing = readExisting(primary, ScoutMethodState.class);
        if (existing == null
                || (isEmptyOrEquals(existing.getFullClassName(), fullClassName)
                && isEmptyOrEquals(existing.getMethodSignature(), methodSignature))) {
            return primary;
        }
        return stateRoot.resolve("methods")
                .resolve(safe(fullClassName))
                .resolve(safe(methodSignature) + "__" + shortHash(fullClassName + "\n" + methodSignature) + ".json");
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_")
                .replace('.', '_');
    }

    private <T> T readExisting(Path path, Class<T> type) {
        long startNanos = System.nanoTime();
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return GSON.fromJson(json, type);
        } catch (IOException | JsonSyntaxException ignored) {
            return null;
        } finally {
            recordStateTiming(startNanos);
        }
    }

    private void recordStateTiming(long startNanos) {
        if (config != null) {
            config.recordTimingSince("state_io", startNanos);
        }
    }

    private boolean isEmptyOrEquals(String value, String expected) {
        return value == null || value.isEmpty() || value.equals(expected);
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[12];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < 6; i++) {
                hex[i * 2] = digits[(bytes[i] >> 4) & 0x0f];
                hex[i * 2 + 1] = digits[bytes[i] & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String simpleClassName(String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fullClassName.length() - 1) {
            return fullClassName;
        }
        return fullClassName.substring(lastDot + 1);
    }
}
