package io.ajdm.engine;

import io.ajdm.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Deletes only an explicitly planned job's files. No traversal, process, or network operations. */
final class RemovalFiles {
    private RemovalFiles() { }
    private record Candidate(Path path, BasicFileAttributes attributes, Object parentKey) { }
    static void delete(List<DownloadRecord> downloads, List<MediaJob> media, Set<Path> protectedFiles) throws IOException {
        var paths = new LinkedHashSet<Path>();
        for (var record : downloads) {
            Path part = record.partialPath().toAbsolutePath().normalize(), target = record.request().target();
            if (!part.getParent().equals(target.getParent()) || !part.getFileName().toString().startsWith(".ajdm-") || !part.getFileName().toString().endsWith(".ajdmpart"))
                throw new DownloadException(ErrorCode.STATE_STORE_ERROR, "Invalid saved partial path");
            paths.add(part);
            // A failed/canceled request may point at somebody's pre-existing destination. Keep that file.
            if (record.state() == DownloadState.COMPLETED) paths.add(target);
        }
        for (var job : media) {
            paths.add(job.videoPath()); paths.add(job.audioPath()); paths.add(job.stagePath());
            if (job.state() == DownloadState.COMPLETED) paths.add(job.target());
        }
        var candidates = new ArrayList<Candidate>();
        for (Path path : paths) {
            if (protectedFiles.contains(path) && Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("Job file is still used by another download; remove the entry while keeping files");
            for (Path parent = path.getParent(); parent != null; parent = parent.getParent())
                if (Files.isSymbolicLink(parent)) throw new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Deletion path crosses a symbolic link");
            try {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) throw new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Refusing to delete a non-regular job file");
                var parent = Files.readAttributes(path.getParent(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                candidates.add(new Candidate(path, attributes, parent.fileKey()));
            } catch (NoSuchFileException missing) { /* A previous deletion attempt or successful publication already removed it. */ }
        }
        // Preflight every path before deleting any. Keep metadata on failure so the user can retry.
        for (var candidate : candidates) {
            try (var directory = Files.newDirectoryStream(candidate.path().getParent())) {
                if (directory instanceof SecureDirectoryStream<Path> secure) {
                    if (!Objects.equals(candidate.parentKey(), secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()))
                        throw new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Job directory changed");
                    Path leaf = candidate.path().getFileName();
                    var current = secure.getFileAttributeView(leaf, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                    verify(candidate.attributes(), current); secure.deleteFile(leaf);
                } else {
                    for (Path parent = candidate.path().getParent(); parent != null; parent = parent.getParent())
                        if (Files.isSymbolicLink(parent)) throw new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Job directory changed");
                    var current = Files.readAttributes(candidate.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    verify(candidate.attributes(), current); Files.delete(candidate.path());
                }
            } catch (NoSuchFileException missing) { /* Idempotent retry; never follow a replacement link. */ }
        }
        for (var job : media) {
            try { if (!Files.isSymbolicLink(job.workspace())) Files.deleteIfExists(job.workspace()); }
            catch (DirectoryNotEmptyException keepUnexpectedFiles) { /* Never delete extra files recursively. */ }
        }
    }
    private static void verify(BasicFileAttributes before, BasicFileAttributes current) throws IOException {
        if (!current.isRegularFile() || !Objects.equals(before.fileKey(), current.fileKey()) || before.size() != current.size() || !before.lastModifiedTime().equals(current.lastModifiedTime()))
            throw new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Job file changed before deletion");
    }
}
