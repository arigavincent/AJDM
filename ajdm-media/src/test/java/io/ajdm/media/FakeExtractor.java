package io.ajdm.media;

/** Child-JVM fixture: records arguments, emits metadata, exercises process limits; no payload transfer. */
public final class FakeExtractor {
    private FakeExtractor() {}
    public static void main(String[] args) throws Exception {
        String page = args[args.length - 1];
        int cookieIndex = java.util.List.of(args).indexOf("--cookies");
        if (cookieIndex >= 0) {
            var copy = java.nio.file.Path.of(args[cookieIndex + 1]);
            boolean browser = java.util.List.of(args).contains("--cookies-from-browser");
            if (!java.nio.file.Files.readString(copy).contains(browser ? "# Netscape HTTP Cookie File" : "test-cookie")) throw new IllegalStateException("Cookie copy missing");
            if (!ownerOnly(copy)) throw new IllegalStateException("Cookie copy not private");
            java.nio.file.Files.writeString(copy, "# Netscape HTTP Cookie File\n# changed by fixture\n");
        }
        if (page.contains("access-blocked")) { System.err.print("ERROR: Cannot parse data; Instagram sent an empty media response " + page); System.exit(1); }
        if (page.contains("auth-blocked")) { System.err.print("ERROR: Login required " + page); System.exit(1); }
        if (page.contains("session-blocked")) { System.err.print("ERROR: Could not copy Chrome cookie database /private/secret " + page); System.exit(1); }
        if (page.contains("child")) {
            var child = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                    System.getProperty("java.class.path"), FakeExtractor.class.getName(), "https://site.test/sleep").inheritIO().start();
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("ajdm.fake.pidFile")), Long.toString(child.pid()));
            Thread.sleep(60_000); return;
        }
        if (page.contains("sleep")) { Thread.sleep(60_000); return; }
        if (page.contains("oversize")) { System.out.print("x".repeat(200_000)); return; }
        if (page.contains("stderr")) { System.err.print("secret".repeat(60_000)); return; }
        if (page.contains("fail")) { System.err.print("secret signed URL " + page); System.exit(1); }
        String arguments = String.join("|", args).replace("\\", "\\\\").replace("\"", "\\\"");
        if (java.util.List.of(args).contains("--flat-playlist")) {
            System.out.print("{\"_type\":\"playlist\",\"id\":\"fixture-list\",\"title\":\"" + arguments + "\",\"entries\":[{\"id\":\"v\",\"url\":\"https://page.test/v\"},null]}"); return;
        }
        System.out.print("{\"id\":\"fixture\",\"title\":\"" + arguments + "\",\"formats\":[{\"format_id\":\"18\",\"ext\":\"mp4\",\"vcodec\":\"avc1\",\"acodec\":\"mp4a\",\"protocol\":\"https\",\"url\":\"https://cdn.test/file\"}]}");
    }
    /** POSIX: exactly rw-------. ACL filesystems: owned by this user and no ALLOW entry for anyone else. */
    private static boolean ownerOnly(java.nio.file.Path file) throws java.io.IOException {
        if (java.nio.file.Files.getFileStore(file).supportsFileAttributeView("posix"))
            return java.nio.file.Files.getPosixFilePermissions(file).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var view = java.nio.file.Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        var user = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if (view == null || !view.getOwner().equals(user)) return false;
        boolean owner = false;
        for (var entry : view.getAcl()) {
            if (entry.type() != java.nio.file.attribute.AclEntryType.ALLOW || entry.permissions().isEmpty()) continue;
            if (!entry.principal().equals(user)) return false;
            owner |= entry.permissions().contains(java.nio.file.attribute.AclEntryPermission.READ_DATA);
        }
        return owner;
    }
}
