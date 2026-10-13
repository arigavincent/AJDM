package io.ajdm.ipc;

import com.google.gson.JsonObject;
import io.ajdm.model.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Windows local control: owner-only descriptor, exclusive ownership and a fresh 256-bit capability. */
final class LoopbackEndpoint implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final String token;
    private LoopbackEndpoint(FileChannel channel, FileLock lock) {
        this.channel = channel; this.lock = lock;
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret); token = HexFormat.of().formatHex(secret);
    }
    static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
    static LoopbackEndpoint acquire(Path file) throws IOException {
        PrivateFiles.directory(file.getParent());
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        var channel = FileChannel.open(lockPath, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), PrivateFiles.attributes(file.getParent(), false));
        FileLock lock = null;
        try {
            PrivateFiles.check(lockPath, false);
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) { }
            if (lock == null) throw new DownloadException(ErrorCode.STATE_IN_USE, "Endpoint already owned");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { read(file); Files.delete(file); }
            return new LoopbackEndpoint(channel, lock);
        } catch (IOException | RuntimeException e) { if (lock != null) lock.close(); channel.close(); throw e; }
    }
    void publish(Path file, int port) throws IOException {
        var json = new JsonObject(); json.addProperty("kind", "AJDM-LOOPBACK-1"); json.addProperty("port", port); json.addProperty("token", token);
        Path temp = Files.createTempFile(file.getParent(), ".endpoint-", ".tmp", PrivateFiles.attributes(file.getParent(), false));
        try {
            Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            PrivateFiles.check(file, false);
        } finally { Files.deleteIfExists(temp); }
    }
    boolean authenticate(JsonObject request) {
        var supplied = request.remove("auth");
        if (supplied == null || !supplied.isJsonPrimitive() || !supplied.getAsJsonPrimitive().isString()) return false;
        String value = supplied.getAsString();
        return value.length() == 64 && MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), value.getBytes(StandardCharsets.US_ASCII));
    }
    static Descriptor read(Path file) throws IOException {
        PrivateFiles.check(file.getParent(), true); PrivateFiles.check(file, false);
        try (var input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (input.size() < 1 || input.size() > 1024) throw new IOException("Invalid endpoint length");
            var data = ByteBuffer.allocate(1025); while (data.hasRemaining() && input.read(data) >= 0) { }
            if (!data.hasRemaining()) throw new IOException("Oversized endpoint");
            var json = Protocol.parse(Arrays.copyOf(data.array(), data.position()));
            int port = Protocol.integer(json, "port", -1); String token = Protocol.string(json, "token", "");
            if (json.size() != 3 || !"AJDM-LOOPBACK-1".equals(Protocol.string(json, "kind", "")) || port < 1 || port > 65535 || !token.matches("[a-f0-9]{64}")) throw new IOException("Invalid endpoint");
            return new Descriptor(port, token);
        } catch (IllegalArgumentException | IllegalStateException e) { throw new IOException("Invalid endpoint", e); }
    }
    record Descriptor(int port, String token) {
        InetSocketAddress address() { return new InetSocketAddress("127.0.0.1", port); }
        @Override public String toString() { return "LocalEndpoint[redacted]"; }
    }
    @Override public void close() throws IOException { try { lock.close(); } finally { channel.close(); } }
}
