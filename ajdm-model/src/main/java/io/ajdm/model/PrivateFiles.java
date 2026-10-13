package io.ajdm.model;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Owner-only application state on POSIX and Windows ACL filesystems; unsupported stores fail closed. */
public final class PrivateFiles {
    private PrivateFiles() { }
    public static void directory(Path path) throws IOException {
        path = path.toAbsolutePath().normalize(); ancestors(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = path.getParent();
            if (parent == null) throw new IOException("Missing private parent");
            Files.createDirectories(parent);
            Files.createDirectory(path, attributes(parent, true));
            initializeNew(path);
        }
        check(path, true);
    }
    public static FileAttribute<?> attributes(Path existing, boolean directory) throws IOException {
        if (Files.getFileStore(existing).supportsFileAttributeView("posix"))
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        if (!Files.getFileStore(existing).supportsFileAttributeView("acl")) throw new IOException("Private permissions unavailable");
        var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user(existing))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        List<AclEntry> acl = List.of(builder.build());
        return new FileAttribute<List<AclEntry>>() {
            @Override public String name() { return "acl:acl"; }
            @Override public List<AclEntry> value() { return acl; }
        };
    }
    /** Called only immediately after creating a new object with restrictive initial attributes. */
    public static void initializeNew(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("acl")) {
            var view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            UserPrincipal current = user(path);
            if (!view.getOwner().equals(current)) view.setOwner(current);
        }
    }
    public static void check(Path path, boolean directory) throws IOException {
        ancestors(path);
        var basic = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (directory ? !basic.isDirectory() : !basic.isRegularFile()) throw new IOException("Invalid private file type");
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var expected = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
            if (!attrs.owner().equals(user(path)) || !attrs.permissions().equals(expected)) throw new IOException("Private permissions invalid");
        } else {
            var view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException("Private ACL unavailable");
            UserPrincipal current = user(path); boolean allowed = false;
            if (!view.getOwner().equals(current)) throw new IOException("Private file owner invalid");
            for (var entry : view.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW && !entry.permissions().isEmpty()) {
                    if (!entry.principal().equals(current)) throw new IOException("Private ACL grants another principal access");
                    if (entry.permissions().contains(AclEntryPermission.READ_DATA) && entry.permissions().contains(AclEntryPermission.WRITE_DATA)) allowed = true;
                }
            }
            if (!allowed) throw new IOException("Private ACL missing owner access");
        }
    }
    private static UserPrincipal user(Path path) throws IOException {
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    }
    public static void ancestors(Path path) throws IOException {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Private path contains a symbolic link");
    }
}
