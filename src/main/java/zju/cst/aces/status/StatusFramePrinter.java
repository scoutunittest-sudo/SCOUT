package zju.cst.aces.status;

import java.io.PrintStream;

final class StatusFramePrinter {
    static final String CLEAR_SCREEN = "\u001B[2J\u001B[H";
    private static final String COLUMNS_PROPERTY = "chatunitest.terminal.columns";
    private static final String ROWS_PROPERTY = "chatunitest.terminal.rows";

    private StatusFramePrinter() {
    }

    static void print(PrintStream out, String frame) {
        out.print(CLEAR_SCREEN);
        out.print(center(frame));
        out.flush();
    }

    private static String center(String frame) {
        TerminalSize terminalSize = terminalSize();
        if (!terminalSize.isKnown()) {
            return frame;
        }
        String[] lines = frame.split("\\R", -1);
        int frameWidth = frameWidth(lines);
        int topPadding = Math.max(0, (terminalSize.rows - lines.length) / 2);
        int leftPadding = Math.max(0, (terminalSize.columns - frameWidth) / 2);
        String left = repeat(' ', leftPadding);

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < topPadding; i++) {
            builder.append('\n');
        }
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(left).append(lines[i]);
        }
        return builder.toString();
    }

    private static int frameWidth(String[] lines) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, stripAnsi(line).length());
        }
        return width;
    }

    private static TerminalSize terminalSize() {
        int columns = positiveInt(System.getProperty(COLUMNS_PROPERTY));
        int rows = positiveInt(System.getProperty(ROWS_PROPERTY));
        if (columns <= 0) {
            columns = positiveInt(System.getenv("COLUMNS"));
        }
        if (rows <= 0) {
            rows = positiveInt(System.getenv("LINES"));
        }
        return new TerminalSize(columns, rows);
    }

    private static int positiveInt(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private static class TerminalSize {
        private final int columns;
        private final int rows;

        private TerminalSize(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
        }

        private boolean isKnown() {
            return columns > 0 && rows > 0;
        }
    }
}
