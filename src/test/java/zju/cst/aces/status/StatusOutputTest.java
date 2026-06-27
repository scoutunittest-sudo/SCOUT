package zju.cst.aces.status;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusOutputTest {

    @Test
    void flushesDeferredDiagnosticsOnceWithoutExposingApiKeys() {
        StatusOutput.deferDiagnostic("HTTP 401 for key sk-proj-super-secret-value");
        StatusOutput.deferDiagnostic("HTTP 401 for key sk-proj-super-secret-value");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StatusOutput.flushDiagnostics(new PrintStream(output));
        String rendered = output.toString();

        assertTrue(rendered.contains("HTTP 401"));
        assertTrue(rendered.contains("[REDACTED_API_KEY]"));
        assertFalse(rendered.contains("sk-proj-super-secret-value"));
        assertEquals(1, occurrences(rendered, "HTTP 401"));

        ByteArrayOutputStream secondOutput = new ByteArrayOutputStream();
        StatusOutput.flushDiagnostics(new PrintStream(secondOutput));
        assertEquals("", secondOutput.toString());
    }

    private int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
