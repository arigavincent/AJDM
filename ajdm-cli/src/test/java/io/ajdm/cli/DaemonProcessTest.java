package io.ajdm.cli;

import com.google.gson.*;
import io.ajdm.bench.FaultyRangeServer;
import io.ajdm.ipc.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class DaemonProcessTest {
    @TempDir Path temp;
    private int log;
    private Path socket() { return temp.resolve("ipc/ajdm.sock"); }
    private Process launch(String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java").toString()); command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp"); command.add(System.getProperty("ajdm.test.classpath")); command.add("io.ajdm.cli.AjdmCli");
        command.add("--socket"); command.add(socket().toString()); command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectError(temp.resolve("error-" + log + ".txt").toFile())
                .redirectOutput(temp.resolve("out-" + log++ + ".txt").toFile()).start();
    }
    private String invoke(int expected, String... args) throws Exception {
        int number = log; Process process = launch(args);
        try {
            assertTrue(process.waitFor(12, TimeUnit.SECONDS), "CLI command timed out");
            assertEquals(expected, process.exitValue(), Files.readString(temp.resolve("error-" + number + ".txt")));
            return Files.readString(temp.resolve("out-" + number + ".txt")).strip();
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }
    private Process daemon() throws Exception {
        Process process = launch("daemon", "--state", temp.resolve("state/ajdm.db").toString());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try { new IpcClient(socket()).call("ping", new JsonObject()); return process; }
            catch (java.io.IOException e) { if (!process.isAlive()) break; Thread.sleep(20); }
        }
        process.destroyForcibly().waitFor(); fail("Daemon did not start"); return process;
    }
    private JsonObject row(String id) throws Exception {
        for (var e : new IpcClient(socket()).call("list", new JsonObject()).getAsJsonArray("downloads")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        }
        throw new AssertionError("Missing download");
    }
    private void await(String id, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (row(id).get("state").getAsString().equals(state)) return; Thread.sleep(20);
        }
        fail("Expected state " + state + ": " + row(id));
    }
    /** POSIX: SIGTERM runs the shutdown hook. Windows: Process.destroy is TerminateProcess, so use the core's own shutdown command as PackagedLauncher does. */
    private void stop(Process process) throws Exception {
        if (FakeTool.windows()) new IpcClient(socket()).call("shutdown", new JsonObject()); else process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail("Daemon did not stop cleanly"); }
    }
    @Test void independentClientsCompleteFileAndDaemonIsSingleOwner() throws Exception {
        try (var fixture = new FaultyRangeServer(200_017, FaultyRangeServer.Mode.NORMAL)) {
            Process process = daemon();
            try {
                assertTrue(invoke(0, "daemon", "--state", temp.resolve("state/ajdm.db").toString()).contains("already running"));
                String id = invoke(0, "add", fixture.uri().toString(), "--directory", temp.resolve("files").toString(),
                        "--filename", "result.bin", "--sha256", fixture.sha256());
                UUID.fromString(id); await(id, "COMPLETED");
                assertArrayEquals(fixture.data(), Files.readAllBytes(temp.resolve("files/result.bin")));
                assertTrue(invoke(0, "list", "--json").contains(id));
                assertTrue(invoke(0, "ping").contains("running"));
                invoke(3, "resume", id); invoke(7, "formats", "https://example.test/video");
                invoke(2, "add", "file:///tmp/source"); invoke(2, "cancel", "not-a-uuid-secret");
            } finally { stop(process); }
            assertFalse(Files.exists(socket())); invoke(4, "list");
        }
    }
    @Test void killedCoreRetainsCheckpointAndResumesFromNewProcessWithoutAutoStart() throws Exception {
        try (var fixture = new FaultyRangeServer(8 * 1024 * 1024 + 17, FaultyRangeServer.Mode.CAPPED, 512 * 1024)) {
            String id; long saved;
            Process first = daemon();
            try {
                id = invoke(0, "add", fixture.uri().toString(), "--directory", temp.resolve("files").toString(),
                        "--filename", "resume.bin", "--sha256", fixture.sha256(), "--connections", "1");
                assertTrue(fixture.awaitTransfer());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                while (row(id).get("durableBytes").getAsLong() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
                saved = row(id).get("durableBytes").getAsLong(); assertTrue(saved > 0);
                assertNotEquals("COMPLETED", row(id).get("state").getAsString());
                first.destroyForcibly(); assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            } finally { if (first.isAlive()) first.destroyForcibly().waitFor(); }
            Process second = daemon();
            try {
                assertEquals("PAUSED", row(id).get("state").getAsString()); assertTrue(row(id).get("durableBytes").getAsLong() >= saved);
                int before = fixture.requests(); Thread.sleep(200); assertEquals(before, fixture.requests());
                invoke(0, "resume", id); await(id, "COMPLETED");
                assertArrayEquals(fixture.data(), Files.readAllBytes(temp.resolve("files/resume.bin")));
                assertTrue(fixture.requestedRanges().stream().anyMatch(r -> r.start() >= saved));
            } finally { stop(second); }
        }
    }
    @Test void terminationSignalSavesActiveTransferAndWaitClientCompletesAfterResume() throws Exception {
        try (var fixture = new FaultyRangeServer(5 * 1024 * 1024 + 17, FaultyRangeServer.Mode.SLOW)) {
            String id; Process first = daemon();
            try {
                id = invoke(0, "add", fixture.uri().toString(), "--directory", temp.resolve("files").toString(),
                        "--filename", "signal.bin", "--sha256", fixture.sha256(), "--connections", "1");
                assertTrue(fixture.awaitTransfer());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (row(id).get("writtenBytes").getAsLong() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(row(id).get("writtenBytes").getAsLong() > 0); stop(first);
            } finally { if (first.isAlive()) first.destroyForcibly().waitFor(); }
            Process second = daemon();
            try {
                assertEquals("PAUSED", row(id).get("state").getAsString()); assertTrue(row(id).get("durableBytes").getAsLong() > 0);
                invoke(0, "resume", id); await(id, "COMPLETED");
                assertArrayEquals(fixture.data(), Files.readAllBytes(temp.resolve("files/signal.bin")));
                assertTrue(invoke(0, "add", fixture.uri().toString(), "--directory", temp.resolve("files").toString(),
                        "--filename", "wait.bin", "--connections", "4", "--wait").contains("Saved"));
                assertArrayEquals(fixture.data(), Files.readAllBytes(temp.resolve("files/wait.bin")));
            } finally { stop(second); }
        }
    }
}
