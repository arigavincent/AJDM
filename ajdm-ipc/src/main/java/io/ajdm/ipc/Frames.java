package io.ajdm.ipc;

import com.google.gson.JsonObject;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.Duration;

/** Deadline bounds connect/read/write, including peers that dribble one byte at a time. */
final class Frames {
    private Frames() {}
    static void connect(SocketChannel channel, java.net.SocketAddress address, Duration timeout) throws IOException {
        channel.configureBlocking(false);
        if (channel.connect(address)) return;
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Selector selector = Selector.open()) {
            channel.register(selector, SelectionKey.OP_CONNECT);
            while (!channel.finishConnect()) ready(selector, deadline);
        }
    }
    static JsonObject read(SocketChannel channel, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        ByteBuffer length = ByteBuffer.allocate(4); transfer(channel, length, false, deadline); length.flip();
        int size = length.getInt(); if (size < 1 || size > Protocol.MAX_BYTES) throw new IOException("Invalid frame length");
        ByteBuffer bytes = ByteBuffer.allocate(size); transfer(channel, bytes, false, deadline);
        return Protocol.parse(bytes.array());
    }
    static void write(SocketChannel channel, JsonObject value, Duration timeout) throws IOException {
        byte[] bytes = Protocol.encode(value); long deadline = System.nanoTime() + timeout.toNanos();
        ByteBuffer length = ByteBuffer.allocate(4).putInt(bytes.length); length.flip();
        transfer(channel, length, true, deadline); transfer(channel, ByteBuffer.wrap(bytes), true, deadline);
    }
    private static void transfer(SocketChannel channel, ByteBuffer bytes, boolean writing, long deadline) throws IOException {
        channel.configureBlocking(false);
        try (Selector selector = Selector.open()) {
            channel.register(selector, writing ? SelectionKey.OP_WRITE : SelectionKey.OP_READ);
            while (bytes.hasRemaining()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("IPC interrupted");
                if (System.nanoTime() >= deadline) throw new IOException("IPC deadline exceeded");
                int count = writing ? channel.write(bytes) : channel.read(bytes);
                if (count < 0) throw new EOFException("Incomplete IPC frame");
                if (count == 0) ready(selector, deadline);
            }
        }
    }
    private static void ready(Selector selector, long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("IPC deadline exceeded");
        selector.select(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
        selector.selectedKeys().clear();
    }
}
