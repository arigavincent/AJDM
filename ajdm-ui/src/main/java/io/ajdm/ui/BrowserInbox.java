package io.ajdm.ui;

import com.google.gson.JsonObject;
import io.ajdm.ipc.IpcClient;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Links offered by the browser extension (and the clipboard of other AJDM windows): one background thread waits on
 * the core ({@code waitOffers}), so a link opens its Download File Info box the moment the browser hands it over
 * instead of on the next poll. When the core is unreachable it retries every second.
 */
final class BrowserInbox implements AutoCloseable {
    record Offer(String url, String referer, String filename, boolean media, long sizeBytes, String formatId, String audioId) {}
    private final Supplier<Path> socket;
    private final Consumer<Offer> sink;
    private final Thread thread;
    private volatile boolean closed;

    BrowserInbox(Supplier<Path> socket, Consumer<Offer> sink) {
        this.socket = socket; this.sink = sink;
        thread = Thread.ofPlatform().daemon().name("ajdm-ui-inbox").unstarted(this::run);
    }
    void start() { thread.start(); }
    private void run() {
        while (!closed) {
            try {
                var request = new JsonObject(); request.addProperty("timeoutMs", 25_000);
                var result = new IpcClient(socket.get()).call("waitOffers", request);
                for (var element : result.getAsJsonArray("offers")) sink.accept(parse(element.getAsJsonObject()));
            } catch (Exception unavailable) {
                try { Thread.sleep(1000); } catch (InterruptedException interrupted) { return; }
            }
        }
    }
    static Offer parse(JsonObject offer) {
        return new Offer(offer.get("url").getAsString(), text(offer, "referer"), text(offer, "filename"), offer.has("media") && offer.get("media").getAsBoolean(),
                offer.has("sizeBytes") ? offer.get("sizeBytes").getAsLong() : -1, text(offer, "formatId"), text(offer, "audioId"));
    }
    private static String text(JsonObject offer, String key) { return offer.has(key) && !offer.get(key).isJsonNull() ? offer.get(key).getAsString() : null; }
    @Override public void close() { closed = true; thread.interrupt(); }
}
