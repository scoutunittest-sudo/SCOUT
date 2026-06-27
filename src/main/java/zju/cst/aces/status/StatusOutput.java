package zju.cst.aces.status;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class StatusOutput {
    private static final int MAX_DIAGNOSTIC_LENGTH = 1200;
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;
    private static final PrintStream SILENT = new PrintStream(new OutputStream() {
        @Override
        public void write(int b) {
        }
    });
    private static final Set<String> DEFERRED_DIAGNOSTICS = new LinkedHashSet<>();
    private static boolean installed;

    private StatusOutput() {
    }

    public static PrintStream out() {
        return ORIGINAL_OUT;
    }

    public static void installStatusOnly() {
        if (installed) {
            return;
        }
        synchronized (DEFERRED_DIAGNOSTICS) {
            DEFERRED_DIAGNOSTICS.clear();
        }
        System.setOut(SILENT);
        System.setErr(SILENT);
        installed = true;
    }

    public static void restore() {
        if (!installed) {
            return;
        }
        System.setOut(ORIGINAL_OUT);
        System.setErr(ORIGINAL_ERR);
        installed = false;
        flushDiagnostics(ORIGINAL_ERR);
    }

    public static void deferDiagnostic(String diagnostic) {
        String sanitized = sanitizeDiagnostic(diagnostic);
        if (sanitized.isEmpty()) {
            return;
        }
        synchronized (DEFERRED_DIAGNOSTICS) {
            DEFERRED_DIAGNOSTICS.add(sanitized);
        }
    }

    public static void flushDiagnostics(PrintStream output) {
        if (output == null) {
            return;
        }
        List<String> diagnostics;
        synchronized (DEFERRED_DIAGNOSTICS) {
            if (DEFERRED_DIAGNOSTICS.isEmpty()) {
                return;
            }
            diagnostics = new ArrayList<>(DEFERRED_DIAGNOSTICS);
            DEFERRED_DIAGNOSTICS.clear();
        }
        output.println();
        output.println("ChatUniTest diagnostics:");
        for (String diagnostic : diagnostics) {
            output.println("- " + diagnostic);
        }
    }

    private static String sanitizeDiagnostic(String diagnostic) {
        if (diagnostic == null) {
            return "";
        }
        String sanitized = API_KEY_PATTERN.matcher(diagnostic).replaceAll("[REDACTED_API_KEY]")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (sanitized.length() > MAX_DIAGNOSTIC_LENGTH) {
            return sanitized.substring(0, MAX_DIAGNOSTIC_LENGTH) + "...";
        }
        return sanitized;
    }
}
