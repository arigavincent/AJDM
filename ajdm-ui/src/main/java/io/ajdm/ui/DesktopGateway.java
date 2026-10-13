package io.ajdm.ui;

import com.google.gson.JsonObject;
import io.ajdm.ipc.IpcClient;
import io.ajdm.model.DownloadException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

/** Client-only boundary: never constructs an engine, store or extractor. No command replay. */
final class DesktopGateway {
    static Path localTarget(DownloadRow row, boolean folder) {
        Path target = Path.of(row.target()).toAbsolutePath().normalize();
        if (!folder && (!row.state().equals("COMPLETED") || row.kind().equals("PLAYLIST"))) throw new IllegalArgumentException("File is not complete");
        Path path = folder && !row.kind().equals("PLAYLIST") ? target.getParent() : target;
        if (folder ? !java.nio.file.Files.isDirectory(path) : !java.nio.file.Files.isRegularFile(path)) throw new IllegalArgumentException("Path unavailable");
        return path;
    }
    static void openLocal(DownloadRow row, boolean folder) {
        Path path = localTarget(row, folder);
        try {
            if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN))
                throw new IllegalStateException("Desktop unavailable");
            java.awt.Desktop.getDesktop().open(path.toFile());
        } catch (java.io.IOException e) { throw new IllegalStateException("Open failed"); }
    }
    private final IpcClient client;
    private final RateTracker rates = new RateTracker();
    DesktopGateway(Path socket) { client = new IpcClient(socket); }
    JsonObject call(String type, JsonObject payload) throws IOException { return client.call(type, payload); }
    synchronized List<DownloadRow> downloads(long now) throws IOException {
        var rows = call("list", new JsonObject()).getAsJsonArray("downloads");
        var result = StreamSupport.stream(rows.spliterator(), false)
                .map(row -> DownloadRow.parse(row.getAsJsonObject(), rates, now)).toList();
        rates.retain(result.stream().map(DownloadRow::id).collect(java.util.stream.Collectors.toSet()));
        return result;
    }
    void control(String command, UUID id, Integer connections) throws IOException {
        if (!java.util.Set.of("pause", "resume", "cancel", "restart", "remove").contains(command)) throw new IllegalArgumentException("Unsupported control");
        var payload = new JsonObject(); payload.addProperty("downloadId", id.toString());
        if (connections != null) {
            if (!command.equals("resume") || connections < 1 || connections > 32) throw new IllegalArgumentException("Invalid connections");
            payload.addProperty("connections", connections);
        }
        call(command, payload);
    }
    synchronized void resetRates() { rates.clear(); }
    static String failure(Throwable failure) {
        return failure instanceof DownloadException known ? known.userMessage()
                : "Could not complete the request. Check your inputs and connection to the core.";
    }
}
