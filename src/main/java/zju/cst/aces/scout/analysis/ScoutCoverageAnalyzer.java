package zju.cst.aces.scout.analysis;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.coverage.CodeCoverageAnalyzer;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.dto.TestMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class ScoutCoverageAnalyzer {
    private static final Logger FALLBACK_LOGGER = Logger.getLogger(ScoutCoverageAnalyzer.class.getName());

    interface CoverageAnalyzerAdapter {
        Map<String, Object> analyzeCoverage(String testSourceCode, String targetTestName,
                                            String targetClassName, String methodSignature,
                                            String targetClassCompiledDir, String targetClassSourceDir,
                                            List<String> dependencies) throws Exception;
    }

    private final Config config;
    private final ScoutUncoveredRegionMapper uncoveredRegionMapper;
    private final CoverageAnalyzerAdapter coverageAnalyzerAdapter;

    public ScoutCoverageAnalyzer(Config config) {
        this(config, new CoverageAnalyzerAdapter() {
            @Override
            public Map<String, Object> analyzeCoverage(String testSourceCode, String targetTestName,
                                                       String targetClassName, String methodSignature,
                                                       String targetClassCompiledDir, String targetClassSourceDir,
                                                       List<String> dependencies) throws Exception {
                return new CodeCoverageAnalyzer().analyzeCoverage(
                        testSourceCode,
                        targetTestName,
                        targetClassName,
                        methodSignature,
                        targetClassCompiledDir,
                        targetClassSourceDir,
                        dependencies);
            }
        });
    }

    ScoutCoverageAnalyzer(Config config, CoverageAnalyzerAdapter coverageAnalyzerAdapter) {
        this.config = config;
        this.uncoveredRegionMapper = new ScoutUncoveredRegionMapper();
        this.coverageAnalyzerAdapter = coverageAnalyzerAdapter;
    }

    public ScoutCoverageResult analyze(String testCode, String fullTestName, PromptInfo promptInfo) {
        long startNanos = System.nanoTime();
        Config.ResourceLease lease = config == null ? null : config.acquireResource("coverage");
        ScoutCoverageResult result = new ScoutCoverageResult();
        try {
            Map<String, Object> raw = coverageAnalyzerAdapter.analyzeCoverage(
                    testCode,
                    fullTestName,
                    promptInfo.getFullClassName(),
                    promptInfo.getMethodSignature(),
                    config.getProject().getBuildPath().toString(),
                    firstCompileSourceRoot(),
                    safeTestClassPaths());

            result.setInstructionCoverage(doubleValue(raw.get("instructionCoverage")));
            result.setBranchCoverage(doubleValue(raw.get("branchCoverage")));
            result.setLineCoverage(doubleValue(raw.get("lineCoverage")));
            result.setMethodCode(stringValue(raw.get("methodCode")));
            applyValidationSummary(result, raw);

            // System.out.println("TESTSSSS");
            // System.out.println(testCode);

            List<Integer> uncoveredLines = parseUncoveredLines(raw.get("uncoveredLines"));
            result.setUncoveredLines(uncoveredLines);
            result.setUncoveredRegions(uncoveredRegionMapper.map(
                    promptInfo.getClassInfo().getCompilationUnitCode(),
                    uncoveredLines));
        } catch (Exception e) {
            warn("SCOUT coverage analysis failed: " + exceptionSummary(e));
            markFailed(result);
            result.setValidTest(false);
            result.setValidationError(error(TestMessage.ErrorType.COMPILE_ERROR,
                    Collections.singletonList(exceptionSummary(e))));
        } finally {
            if (lease != null) {
                lease.close();
            }
            if (config != null) {
                config.recordTimingSince("coverage", startNanos);
            }
        }
        return result;
    }

    private void applyValidationSummary(ScoutCoverageResult result, Map<String, Object> raw) {
        if (result == null || raw == null
                || (!raw.containsKey("testsFailedCount") && !raw.containsKey("testsSucceededCount"))) {
            return;
        }
        long failed = longValue(raw.get("testsFailedCount"));
        long succeeded = longValue(raw.get("testsSucceededCount"));
        if (failed > 0L || succeeded == 0L) {
            result.setValidTest(false);
            List<String> messages = stringList(raw.get("failureMessages"));
            if (messages.isEmpty()) {
                messages.add(failed + " test(s) failed, " + succeeded + " test(s) succeeded.");
            }
            result.setValidationError(error(TestMessage.ErrorType.RUNTIME_ERROR, messages));
        }
    }

    List<Integer> parseUncoveredLines(Object value) {
        if (!(value instanceof List<?>)) {
            return Collections.emptyList();
        }

        List<Integer> lines = new ArrayList<Integer>();
        for (Object item : (List<?>) value) {
            if (item == null) {
                continue;
            }
            parseUncoveredLineToken(String.valueOf(item).trim(), lines);
        }
        return lines;
    }

    private void parseUncoveredLineToken(String token, List<Integer> lines) {
        if (token.length() == 0 || "none".equalsIgnoreCase(token)) {
            return;
        }

        int dashIndex = token.indexOf('-');
        if (dashIndex > 0 && dashIndex == token.lastIndexOf('-')) {
            addRange(token.substring(0, dashIndex), token.substring(dashIndex + 1), lines);
            return;
        }

        Integer line = parsePositiveInt(token);
        if (line != null) {
            lines.add(line);
        }
    }

    private void addRange(String startToken, String endToken, List<Integer> lines) {
        Integer start = parsePositiveInt(startToken.trim());
        Integer end = parsePositiveInt(endToken.trim());
        if (start == null || end == null || start.intValue() > end.intValue()) {
            return;
        }
        for (int line = start.intValue(); line <= end.intValue(); line++) {
            lines.add(Integer.valueOf(line));
        }
    }

    private Integer parsePositiveInt(String token) {
        try {
            int value = Integer.parseInt(token);
            if (value <= 0) {
                return null;
            }
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstCompileSourceRoot() {
        List<String> sourceRoots = config.getProject().getCompileSourceRoots();
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            throw new IllegalStateException("Project compile source roots are missing");
        }
        return sourceRoots.get(0);
    }

    private List<String> safeTestClassPaths() {
        if (config.getTestClassPaths() == null) {
            return Collections.emptyList();
        }
        return config.getTestClassPaths();
    }

    private double doubleValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return -1.0;
            }
        }
        return -1.0;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (!(value instanceof List<?>)) {
            return result;
        }
        for (Object item : (List<?>) value) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private void markFailed(ScoutCoverageResult result) {
        result.setInstructionCoverage(-1.0);
        result.setBranchCoverage(-1.0);
        result.setLineCoverage(-1.0);
    }

    private TestMessage error(TestMessage.ErrorType type, List<String> messages) {
        TestMessage error = new TestMessage();
        error.setErrorType(type);
        error.setErrorMessage(messages == null ? Collections.<String>emptyList() : messages);
        return error;
    }

    private String exceptionSummary(Exception e) {
        String exceptionClass = e.getClass().getSimpleName();
        if (e.getMessage() == null || e.getMessage().length() == 0) {
            return exceptionClass;
        }
        return exceptionClass + ": " + e.getMessage();
    }

    private void warn(String message) {
        if (config != null && config.getLogger() != null) {
            config.getLogger().warn(message);
            return;
        }
        FALLBACK_LOGGER.warning(message);
    }
}
