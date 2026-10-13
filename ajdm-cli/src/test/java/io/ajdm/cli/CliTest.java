package io.ajdm.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    @TempDir Path temp;
    @Test void unavailableCoreHasStableExitAndAction() {
        var out = new StringWriter(); var err = new StringWriter();
        int result = AjdmCli.execute(new String[]{"--socket", temp.resolve("missing.sock").toString(), "list"}, new PrintWriter(out), new PrintWriter(err));
        assertEquals(4, result); assertTrue(err.toString().contains("AJDM-5001")); assertTrue(err.toString().contains("Start AJDM"));
    }
    @Test void parserDoesNotEchoSecretsOnErrors() {
        var out = new StringWriter(); var err = new StringWriter();
        assertEquals(2, AjdmCli.execute(new String[]{"add", "https://host/?token=private", "--oops=secret"}, new PrintWriter(out), new PrintWriter(err)));
        assertFalse(err.toString().contains("private")); assertFalse(err.toString().contains("secret"));
    }
    @Test void helpWorksWithoutDaemon() {
        var out = new StringWriter(); var err = new StringWriter();
        assertEquals(0, AjdmCli.execute(new String[]{"--help"}, new PrintWriter(out), new PrintWriter(err)));
        assertTrue(out.toString().contains("daemon")); assertTrue(out.toString().contains("formats"));
    }
    @Test void controlHelpUsesItsActualCommandNameAndInheritedSocketOption() {
        var out = new StringWriter(); var err = new StringWriter();
        assertEquals(0, AjdmCli.execute(new String[]{"resume", "--help"}, new PrintWriter(out), new PrintWriter(err)));
        assertTrue(out.toString().contains("Usage: ajdm resume"));
        assertEquals(4, AjdmCli.execute(new String[]{"list", "--socket", temp.resolve("missing.sock").toString()}, new PrintWriter(out), new PrintWriter(err)));
    }
}
