package zju.cst.aces.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusFramePrinterTest {
    @AfterEach
    void clearTerminalSizeProperties() {
        System.clearProperty("chatunitest.terminal.columns");
        System.clearProperty("chatunitest.terminal.rows");
    }

    @Test
    void printCentersFrameWithinConfiguredTerminalSize() {
        System.setProperty("chatunitest.terminal.columns", "20");
        System.setProperty("chatunitest.terminal.rows", "6");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StatusFramePrinter.print(new PrintStream(output), "+----+\n| ok |\n+----+");

        String rendered = output.toString();
        assertTrue(rendered.startsWith("\u001B[2J\u001B[H\n"));
        assertTrue(rendered.contains("       +----+\n"));
        assertTrue(rendered.contains("       | ok |\n"));
        assertTrue(rendered.contains("       +----+"));
    }

    @Test
    void printFallsBackToUncenteredFrameWhenTerminalSizeIsUnknown() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StatusFramePrinter.print(new PrintStream(output), "+----+\n| ok |\n+----+");

        assertEquals("\u001B[2J\u001B[H+----+\n| ok |\n+----+", output.toString());
    }
}
