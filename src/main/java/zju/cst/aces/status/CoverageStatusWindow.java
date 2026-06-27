package zju.cst.aces.status;

import zju.cst.aces.api.config.Config;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.function.LongSupplier;

public class CoverageStatusWindow {
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

    public CoverageStatusWindow() {
        this(StatusOutput.out());
    }

    public CoverageStatusWindow(PrintStream out) {
        this(out, StatusPrintThrottle.DEFAULT_INTERVAL_MILLIS, System::currentTimeMillis);
    }

    CoverageStatusWindow(PrintStream out, long throttleMillis, LongSupplier clock) {
        this.out = out;
        this.printThrottle = new StatusPrintThrottle(throttleMillis, clock);
    }

    public String render(Config config) {
        String phase = value(config == null ? null : config.getPhaseType(), "unknown");
        String step = value(config == null ? null : config.getCurrentStatusStep(), "Coverage");
        String project = config == null || config.getProject() == null || config.getProject().getBasedir() == null
                ? "unknown"
                : config.getProject().getBasedir().getAbsolutePath();
        int completed = config == null || config.getCompletedJobCount() == null ? 0 : config.getCompletedJobCount().get();
        int total = config == null || config.getJobCount() == null ? 0 : config.getJobCount().get();
        int validTests = config == null || config.getValidCoverageTestCount() == null
                ? 0
                : config.getValidCoverageTestCount().get();
        int compilationErrors = config == null || config.getCompilationErrorCount() == null
                ? 0
                : config.getCompilationErrorCount().get();
        int runtimeErrors = config == null || config.getRuntimeErrorCount() == null
                ? 0
                : config.getRuntimeErrorCount().get();

        StringBuilder builder = new StringBuilder();
        builder.append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row(CYAN + "SCOUT Coverage" + RESET)).append("\n")
                .append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row("Phase   : " + phase + " / " + step)).append("\n")
                .append(row("Project : " + project)).append("\n")
                .append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row("Tests   : " + completed + " / " + total + " " + progressBar(completed, total))).append("\n")
                .append(row("Time    : " + timing(config, completed, total))).append("\n")
                .append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row(GREEN + "Valid tests    : " + validTests + RESET)).append("\n")
                .append(row(RED + "Compile errors : " + compilationErrors + RESET)).append("\n")
                .append(row(RED + "Runtime errors : " + runtimeErrors + RESET)).append("\n")
                .append(CYAN).append(BORDER).append(RESET).append("\n")
                .append(row("Output  : " + outputPath(config))).append("\n")
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

    private String outputPath(Config config) {
        if (config == null) {
            return "unknown";
        }
        Path path = config.getStatusOutputPath();
        if (path == null) {
            path = config.getTestOutput();
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

    private String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
