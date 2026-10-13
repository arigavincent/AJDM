package io.ajdm.ipc;

import com.google.gson.JsonObject;
import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import java.io.IOException;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.Set;

/** A thin one-command client: no SQLite or engine instances, and no transport-level automatic replay. */
public final class IpcClient {
    private final Path socket;
    private final boolean loopback;
    public IpcClient(Path socket) { this(socket, LoopbackEndpoint.windows()); }
    IpcClient(Path socket, boolean loopback) { this.socket = socket.toAbsolutePath().normalize(); this.loopback = loopback; }
    public static Path defaultSocket() { return Endpoint.defaultSocket(); }
    public JsonObject call(String type, JsonObject payload) throws IOException {
        String id = UUID.randomUUID().toString();
        var request = Dispatcher.envelope(id); request.addProperty("type", type); request.add("payload", payload);
        // Reject a local oversize command before connecting or causing any side effect.
        Protocol.encode(request);
        try (SocketChannel channel = SocketChannel.open(loopback ? StandardProtocolFamily.INET : StandardProtocolFamily.UNIX)) {
if (loopback) {
                var endpoint = LoopbackEndpoint.read(socket); request.addProperty("auth", endpoint.token());
                Frames.connect(channel, endpoint.address(), Duration.ofSeconds(2));
            } else {
                Endpoint.checkClient(socket);
                Frames.connect(channel, UnixDomainSocketAddress.of(socket), Duration.ofSeconds(2));
            }
            Frames.write(channel, request, Duration.ofSeconds(5));
            var response = Frames.read(channel, Duration.ofSeconds(type.equals("playlistFormats") ? 150 : Set.of("formats", "addMedia", "playlist", "addPlaylist").contains(type) ? 75 : 40));
            if (Protocol.integer(response, "v", -1) != 1 || !id.equals(Protocol.string(response, "id", null))) {
                throw new IOException("Unmatched IPC response");
            }
            if (!response.has("ok")) throw new IOException("Missing result");
            if (!Protocol.bool(response, "ok", false)) {
                if (!response.has("error") || !response.get("error").isJsonObject()) throw new IOException("Invalid error response");
                String code = Protocol.string(response.getAsJsonObject("error"), "code", "");
                for (ErrorCode known : ErrorCode.values()) if (known.publicCode().equals(code)) throw new DownloadException(known, "Core rejected command");
                throw new IOException("Unknown error response");
            }
            if (!response.has("payload") || !response.get("payload").isJsonObject()) throw new IOException("Invalid response");
            return response.getAsJsonObject("payload");
        } catch (DownloadException e) { throw e; }
        catch (IOException | UnsupportedOperationException | IllegalArgumentException | IllegalStateException e) {
            throw new DownloadException(ErrorCode.IPC_UNAVAILABLE, "Core unavailable");
        }
    }
}
