package io.ajdm.ipc;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Set;

/** Private local endpoints; lock ownership precedes removal of a stale socket. */
final class Endpoint implements AutoCloseable {
    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Endpoint(FileChannel channel, FileLock lock) { lockChannel = channel; this.lock = lock; }
    static Path defaultSocket() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        return os.contains("linux") && runtime != null && !runtime.isBlank()
                ? Path.of(runtime).resolve("ajdm/ajdm.sock") : AjdmEngine.defaultDatabase().resolveSibling("ajdm.sock");
    }
    static Endpoint acquire(Path socket) throws IOException {
        Path directory = socket.toAbsolutePath().normalize().getParent();
        if (directory == null) throw new IOException("Missing endpoint directory");
        for (Path p = directory; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("Symlink endpoint ancestor");
        }
        // Unqualified ACL providers fail closed. Native Windows access control is a release gate.
        if (!Files.getFileStore(nearestExisting(directory)).supportsFileAttributeView(PosixFileAttributeView.class)) {
            throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Private endpoint permissions unavailable");
        }
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY));
        check(directory, DIRECTORY, true);
        Path lockPath = socket.resolveSibling(socket.getFileName() + ".lock");
        FileChannel channel = FileChannel.open(lockPath, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(FILE));
        FileLock lock = null;
        try {
            check(lockPath, FILE, false);
            if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid endpoint lock");
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { /* handled below */ }
            if (lock == null) throw new DownloadException(ErrorCode.STATE_IN_USE, "Endpoint already owned");
            if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
                check(socket, FILE, false);
                Object mode = Files.getAttribute(socket, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                if (!(mode instanceof Integer bits) || (bits & 0170000) != 0140000) throw new IOException("Endpoint is not a socket");
                // Protect an existing responding endpoint even if it was started without this lock convention.
                try (SocketChannel peer = SocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
                    Frames.connect(peer, java.net.UnixDomainSocketAddress.of(socket), java.time.Duration.ofSeconds(2));
                } catch (java.net.ConnectException refused) {
                    Files.delete(socket); return new Endpoint(channel, lock);
                }
                throw new DownloadException(ErrorCode.STATE_IN_USE, "Live endpoint exists");
            }
            return new Endpoint(channel, lock);
        } catch (IOException | RuntimeException e) {
            if (lock != null) lock.close(); channel.close(); throw e;
        }
    }
    static void secureSocket(Path socket) throws IOException { Files.setPosixFilePermissions(socket, FILE); check(socket, FILE, false); }
    static void checkClient(Path socket) throws IOException {
        Path parent = socket.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Missing socket directory");
        for (Path p = parent; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException("Symlink endpoint ancestor");
        check(parent, DIRECTORY, true); check(socket, FILE, false);
        Object mode = Files.getAttribute(socket, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        if (!(mode instanceof Integer bits) || (bits & 0170000) != 0140000) throw new IOException("Endpoint is not a socket");
    }
    private static Path nearestExisting(Path path) {
        Path current = path;
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) current = current.getParent();
        return current;
    }
    private static void check(Path path, Set<PosixFilePermission> permissions, boolean directory) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal user = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if (!attributes.owner().equals(user) || !attributes.permissions().equals(permissions) || attributes.isSymbolicLink()
                || directory && !attributes.isDirectory()) throw new IOException("Endpoint permissions or ownership invalid");
    }
    @Override public void close() throws IOException { try { lock.close(); } finally { lockChannel.close(); } }
}
