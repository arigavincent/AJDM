package io.ajdm.ipc;

import io.ajdm.core.CoreService;
import io.ajdm.model.ErrorCode;
import java.io.IOException;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;

/** Private UDS or authenticated Windows loopback. One frame/command/connection; 16 admitted peers, no unbounded accept queue. */
public final class IpcServer implements AutoCloseable {
    private final Path socket;
    private final Endpoint endpoint;
    private final LoopbackEndpoint loopback;
    private final ServerSocketChannel listener;
    private final Object socketKey;
    private final Dispatcher dispatcher;
    private final Duration frameDeadline;
    private final Semaphore peers = new Semaphore(16);
    private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Thread acceptor;
    private volatile boolean closed;
    public IpcServer(Path socket, CoreService core) throws IOException { this(socket, core, Duration.ofSeconds(5)); }
    IpcServer(Path socket, CoreService core, Duration frameDeadline) throws IOException {
        this(socket, core, frameDeadline, LoopbackEndpoint.windows());
    }
    IpcServer(Path socket, CoreService core, Duration frameDeadline, boolean tcp) throws IOException {
        this.socket = socket.toAbsolutePath().normalize(); dispatcher = new Dispatcher(core); this.frameDeadline = frameDeadline;
        endpoint = tcp ? null : Endpoint.acquire(this.socket);
        loopback = tcp ? LoopbackEndpoint.acquire(this.socket) : null;
        ServerSocketChannel channel = null;
        try {
            if (loopback != null) {
                channel = ServerSocketChannel.open(StandardProtocolFamily.INET);
                channel.bind(new InetSocketAddress("127.0.0.1", 0));
                loopback.publish(this.socket, ((InetSocketAddress) channel.getLocalAddress()).getPort());
            } else {
                channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                channel.bind(UnixDomainSocketAddress.of(this.socket)); Endpoint.secureSocket(this.socket);
            }
            socketKey = Files.readAttributes(this.socket, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            listener = channel;
        } catch (IOException | RuntimeException e) {
            if (channel != null) channel.close(); closeEndpoint(); handlers.close(); throw e;
        }
        acceptor = Thread.ofPlatform().name("ajdm-ipc-accept").start(this::accept);
    }
    private void accept() {
        try {
            while (!closed) {
                SocketChannel client = listener.accept();
                if (!peers.tryAcquire()) { client.close(); continue; }
                clients.add(client);
                if (closed) { clients.remove(client); peers.release(); client.close(); break; }
                try { handlers.submit(() -> serve(client)); }
                catch (RejectedExecutionException e) { clients.remove(client); peers.release(); client.close(); }
            }
        } catch (IOException e) { /* Closing the listener terminates accept. No exception details are logged. */ }
        finally { stopped.countDown(); }
    }
    private void serve(SocketChannel channel) {
        try (channel) {
            com.google.gson.JsonObject response; boolean shutdown = false;
            try {
                var request = Frames.read(channel, frameDeadline);
                response = loopback == null || loopback.authenticate(request)
                        ? dispatcher.dispatch(request) : Dispatcher.failure("invalid", ErrorCode.INVALID_REQUEST);
                shutdown = "shutdown".equals(Protocol.string(request, "type", "")) && Protocol.bool(response, "ok", false);
            }
            catch (IOException | RuntimeException e) { response = Dispatcher.failure("invalid", ErrorCode.INVALID_REQUEST); }
            Frames.write(channel, response, frameDeadline);
            if (shutdown) Thread.ofVirtual().start(() -> { try { close(); } catch (IOException ignored) { } });
        } catch (IOException e) { /* A disconnect does not cancel an accepted transfer or replay it. */ }
        finally { clients.remove(channel); peers.release(); }
    }
    private void closeEndpoint() throws IOException { if (endpoint != null) endpoint.close(); if (loopback != null) loopback.close(); }
    public void await() throws InterruptedException { stopped.await(); }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try {
            listener.close();
            for (var client : clients) {
                try { client.close(); } catch (IOException e) { /* Continue closing all admitted peers. */ }
            }
            handlers.shutdownNow();
            try { acceptor.join(5000); handlers.awaitTermination(35, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS) && socketKey != null
                    && socketKey.equals(Files.readAttributes(socket, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) Files.delete(socket);
        } finally { closeEndpoint(); }
    }
}
