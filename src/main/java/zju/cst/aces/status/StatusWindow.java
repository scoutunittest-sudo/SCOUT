package zju.cst.aces.status;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.config.Model;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.function.LongSupplier;

public class StatusWindow {
    private static final int BAR_WIDTH = 20;
    private static final int CONTENT_WIDTH = 58;
    private static final String BORDER = "+------------------------------------------------------------+";
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private final PrintStream out;
    private final StatusPrintThrottle printThrottle;

    public StatusWindow() {
        this(StatusOutput.out());
    }

    public StatusWindow(PrintStream out) {
        this(out, StatusPrintThrottle.DEFAULT_INTERVAL_MILLIS, System::currentTimeMillis);
    }

    StatusWindow(PrintStream out, long throttleMillis, LongSupplier clock) {
        this.out = out;
        this.printThrottle = new StatusPrintThrottle(throttleMillis, clock);
    }

    public String render(Config config) {
        String llm = llmName(config);
        String phase = value(config == null ? null : config.getPhaseType(), "unknown");
        String project = config == null || config.getProject() == null || config.getProject().getBasedir() == null
                ? "unknown"
                : config.getProject().getBasedir().getAbsolutePath();
        int completed = config == null || config.getCompletedJobCount() == null ? 0 : config.getCompletedJobCount().get();
        int total = config == null || config.getJobCount() == null ? 0 : config.getJobCount().get();
        int valid = config == null || config.getValidUnitTestMethodCount() == null ? 0 : config.getValidUnitTestMethodCount().get();
        int compilationErrors = config == null || config.getCompilationErrorCount() == null ? 0 : config.getCompilationErrorCount().get();
        int runtimeErrors = config == null || config.getRuntimeErrorCount() == null ? 0 : config.getRuntimeErrorCount().get();
        int exported = config == null || config.getExportedTestCount() == null ? 0 : config.getExportedTestCount().get();
        int timeouts = config == null || config.getTimeoutCount() == null ? 0 : config.getTimeoutCount().get();
        int llmAttempts = config == null || config.getLlmCallAttemptCount() == null ? 0 : config.getLlmCallAttemptCount().get();
        int llmSuccesses = config == null || config.getLlmCallSuccessCount() == null ? 0 : config.getLlmCallSuccessCount().get();
        int fullyCovered = config == null ? 0 : config.getFullyCoveredMethodCount();
        String phaseLine = phaseLine(config, phase);
        String currentMethod = value(config == null ? null : config.getCurrentStatusMethod(), "waiting");
        String outputPath = outputPath(config);

        StringBuilder builder = new StringBuilder();
        builder.append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row(CYAN + "SCOUT Status" + RESET)).append("\n")
                .append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row("LLM     : " + llm)).append("\n")
                .append(row("Phase   : " + phaseLine)).append("\n")
                .append(row("Mode    : " + executionMode(config))).append("\n");
        if (isMultithreading(config)) {
            builder.append(row("Threads : " + threadLine(config))).append("\n");
            if (isResourceProfileEnabled(config)) {
                builder.append(row("Limits  : " + resourceLimitLine(config, "llm", "compile"))).append("\n")
                        .append(row("          " + resourceLimitLine(config, "run", "coverage"))).append("\n");
            }
        }
        builder
                .append(row("Coverage: " + coverageMode(config))).append("\n")
                .append(row("Project : " + project)).append("\n")
                .append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row(progressLabel(config) + " : " + completed + " / " + total + " " + progressBar(completed, total))).append("\n");
        if (!isMultithreading(config)) {
            builder.append(row("Current : " + currentMethod)).append("\n");
        }
        builder.append(row("Time    : " + timing(config, completed, total))).append("\n")
                .append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row(GREEN + "Valid methods : " + valid + RESET)).append("\n")
                .append(row(GREEN + "Full line cov : " + fullyCovered + RESET)).append("\n")
                .append(row(GREEN + "Exported tests : " + exported + RESET)).append("\n")
                .append(row(RED + "Compile errors : " + compilationErrors + RESET)).append("\n")
                .append(row(RED + "Runtime errors : " + runtimeErrors + RESET)).append("\n")
                .append(row(RED + "Timeouts       : " + timeouts + RESET)).append("\n")
                .append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row("Output  : " + outputPath)).append("\n")
                .append(CYAN).append(BORDER).append(RESET);
        return builder.toString();
    }

    public void print(Config config) {
        // Event-driven repaint is intentionally disabled. StatusTicker owns repaint cadence.
    }

    public void printNow(Config config) {
        StatusFramePrinter.print(out, render(config));
    }

    private boolean isComplete(Config config) {
        if (config == null || config.getJobCount() == null || config.getCompletedJobCount() == null) {
            return false;
        }
        int total = config.getJobCount().get();
        return total > 0 && config.getCompletedJobCount().get() >= total;
    }

    private String llmName(Config config) {
        if (config == null) {
            return "unknown";
        }
        String configured = config.getModelName();
        if (configured != null && configured.trim().length() > 0) {
            return configured.trim();
        }
        Model model = config.getModel();
        if (model != null) {
            return model.getModelName();
        }
        return "unknown";
    }

    private String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String phaseLine(Config config, String phase) {
        if (config == null || isMultithreading(config)) {
            return phase;
        }
        String step = value(config.getCurrentStatusStep(), "");
        return step.isEmpty() ? phase : phase + " / " + step;
    }

    private boolean isMultithreading(Config config) {
        return config != null && config.isEnableMultithreading();
    }

    private boolean isResourceProfileEnabled(Config config) {
        return config != null && config.isResourceProfileEnabled();
    }

    private String executionMode(Config config) {
        boolean resume = config != null && config.isResumeMode();
        return modeLabel(isMultithreading(config), resume);
    }

    static String modeLabel(boolean multithreading, boolean resume) {
        String base = multithreading ? "multithreading" : "single-thread";
        return resume ? base + " (resume)" : base;
    }

    private String threadLine(Config config) {
        int active = config == null || config.getActiveWorkerThreadCount() == null
                ? 0
                : config.getActiveWorkerThreadCount().get();
        int max = config == null ? 0 : Math.max(config.getMaxThreads(), 0);
        int classThreads = config == null ? 0 : Math.max(config.getClassThreads(), 0);
        int methodThreads = config == null ? 0 : Math.max(config.getMethodThreads(), 0);
        return active + " / " + max + " (class " + classThreads + ", method " + methodThreads + ")";
    }

    private String resourceLimitLine(Config config, String first, String second) {
        return resourceLimit(config, first) + ", " + resourceLimit(config, second);
    }

    private String resourceLimit(Config config, String category) {
        if (config == null) {
            return category + " 0/0";
        }
        return category + " "
                + config.getActiveResourceCount(category)
                + "/"
                + config.getResourceLimit(category);
    }

    private String coverageMode(Config config) {
        return config != null && config.isReportCoverage() ? "report on" : "report off";
    }

    private String progressLabel(Config config) {
        String step = config == null ? null : config.getCurrentStatusStep();
        if (step != null && step.trim().equalsIgnoreCase("Coverage")) {
            return "Tests  ";
        }
        return "Methods";
    }

    private String outputPath(Config config) {
        if (config == null) {
            return "unknown";
        }
        Path path = config.getStatusOutputPath();
        if (path == null) {
            path = config.getTestOutput();
        }
        if (path == null) {
            path = config.getScoutRunOutput();
        }
        return path == null ? "unknown" : path.toString();
    }

    private String timing(Config config, int completed, int total) {
        long now = System.currentTimeMillis();
        long start = config == null ? 0L : config.getStatusStartTimeMillis();
        long elapsedMillis = start > 0L ? Math.max(0L, now - start) : 0L;
        String elapsed = formatDuration(elapsedMillis);
        if (completed <= 0 || total <= 0 || completed >= total) {
            return elapsed + " elapsed | ETA --:--:--";
        }
        long etaMillis = Math.round((elapsedMillis * 1.0 / completed) * (total - completed));
        return elapsed + " elapsed | ETA " + formatDuration(etaMillis);
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, remainingSeconds);
    }

    private String progressBar(int completed, int total) {
        if (total <= 0) {
            return YELLOW + "[....................] 0%" + RESET;
        }
        int boundedCompleted = Math.max(0, Math.min(completed, total));
        int filled = (int) Math.round((boundedCompleted * 1.0 / total) * BAR_WIDTH);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < BAR_WIDTH; i++) {
            bar.append(i < filled ? '#' : '.');
        }
        int percent = (int) Math.round((boundedCompleted * 100.0) / total);
        bar.append("] ").append(percent).append('%');
        return YELLOW + bar + RESET;
    }

    private String row(String content) {
        String visible = abbreviate(content == null ? "" : content);
        StringBuilder builder = new StringBuilder("| ");
        builder.append(visible);
        int visibleLength = stripAnsi(visible).length();
        for (int i = visibleLength; i < CONTENT_WIDTH; i++) {
            builder.append(' ');
        }
        builder.append(" |");
        return builder.toString();
    }

    private String abbreviate(String value) {
        if (stripAnsi(value).length() <= CONTENT_WIDTH) {
            return value;
        }
        String plain = stripAnsi(value);
        return "..." + plain.substring(plain.length() - CONTENT_WIDTH + 3);
    }

    private String stripAnsi(String value) {
        return value.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
