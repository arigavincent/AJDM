package io.ajdm.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ajdm.ipc.IpcClient;
import io.ajdm.model.DownloadException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Browser native-messaging host: the AJDM extension's only channel to the core. Each message is a 4-byte
 * native-order length and UTF-8 JSON (the browsers' stdio protocol). The extension keeps one host running over a
 * long-lived port, so a click costs a local round trip instead of starting a JVM; each reply carries the request's
 * {@code id}. Accepted: "ping"; "offer" (a link for the desktop window's Download File Info box); "prefetch" (analyse a
 * video page in the background); "qualities" (the short quality menu for a video page). When AJDM is not running the
 * answer says so and the extension leaves the browser's own download alone.
 */
public final class NativeHost {
    static final int MAX_MESSAGE = 64 * 1024;
    private NativeHost() {}

    public static void main(String[] args) throws IOException {
        Path endpoint = Path.of(System.getProperty("ajdm.endpoint", PackagedCore.defaultProfile().resolve("core.endpoint").toString()));
        serve(System.in, System.out, endpoint);
    }

    static void serve(InputStream in, OutputStream out, Path endpoint) throws IOException {
        for (;;) {
            JsonObject message;
            try { message = read(in); }
            catch (EOFException closed) { return; }
            catch (IOException | RuntimeException invalid) { write(out, failure("Invalid message")); return; }
            var reply = handle(message, endpoint);
            if (message.has("id") && message.get("id").isJsonPrimitive() && message.get("id").getAsJsonPrimitive().isNumber()) reply.add("id", message.get("id"));
            write(out, reply);
        }
    }

    static JsonObject handle(JsonObject message, Path endpoint) {
        String type = message.has("type") && message.get("type").isJsonPrimitive() ? message.get("type").getAsString() : "";
        var client = new IpcClient(endpoint);
        try {
            switch (type) {
                case "ping" -> { client.call("ping", new JsonObject()); return ok(); }
                case "offer" -> {
                    var payload = new JsonObject();
                    for (String key : new String[]{"url", "referer", "filename", "formatId", "audioId"}) {
                        if (message.has(key) && message.get(key).isJsonPrimitive()) payload.addProperty(key, message.get(key).getAsString());
                    }
                    payload.addProperty("media", message.has("media") && message.get("media").isJsonPrimitive() && message.get("media").getAsBoolean());
                    if (message.has("sizeBytes") && message.get("sizeBytes").isJsonPrimitive() && message.get("sizeBytes").getAsJsonPrimitive().isNumber())
                        payload.addProperty("sizeBytes", message.get("sizeBytes").getAsLong());
                    client.call("offer", payload);
                    return ok();
                }
                case "prefetch" -> {
                    var payload = new JsonObject(); payload.addProperty("url", page(message));
                    client.call("prefetch", payload); return ok();
                }
                case "qualities" -> {
                    var payload = new JsonObject(); payload.addProperty("url", page(message)); payload.addProperty("showAll", true);
                    var formats = client.call("formats", payload);
                    var reply = ok(); reply.addProperty("title", formats.has("title") ? formats.get("title").getAsString() : "");
                    reply.add("qualities", QualityMenu.of(formats.getAsJsonArray("formats")));
                    return reply;
                }
                default -> { return failure("Unknown request"); }
            }
        } catch (DownloadException e) {
            return failure(e.code() == io.ajdm.model.ErrorCode.IPC_UNAVAILABLE ? "AJDM is not running" : e.code().publicCode());
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException | UnsupportedOperationException e) { return failure("Invalid request"); }
        catch (IOException | RuntimeException e) { return failure("AJDM is not running"); }
    }

    private static String page(JsonObject message) {
        if (!message.has("url") || !message.get("url").isJsonPrimitive()) throw new IllegalArgumentException("Missing page");
        return message.get("url").getAsString();
    }

    static JsonObject read(InputStream in) throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length == 0) throw new EOFException();
        if (header.length < 4) throw new IOException("Truncated length");
        int length = ByteBuffer.wrap(header).order(ByteOrder.nativeOrder()).getInt();
        if (length <= 0 || length > MAX_MESSAGE) throw new IOException("Message size limit");
        byte[] body = in.readNBytes(length);
        if (body.length != length) throw new IOException("Truncated message");
        var element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        if (!element.isJsonObject()) throw new IOException("Expected an object");
        return element.getAsJsonObject();
    }

    static void write(OutputStream out, JsonObject message) throws IOException {
        byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
        var frame = new ByteArrayOutputStream(body.length + 4);
        frame.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(body.length).array());
        frame.writeBytes(body);
        out.write(frame.toByteArray()); out.flush();
    }

    private static JsonObject ok() { var r = new JsonObject(); r.addProperty("ok", true); return r; }
    private static JsonObject failure(String reason) { var r = new JsonObject(); r.addProperty("ok", false); r.addProperty("error", reason); return r; }
}
