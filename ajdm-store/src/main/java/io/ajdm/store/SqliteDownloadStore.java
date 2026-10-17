package io.ajdm.store;

import io.ajdm.model.*;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Every JDBC operation, including reads and close, runs on one dedicated platform thread. */
public final class SqliteDownloadStore implements DownloadStore {
    private static final Set<String> SAFE_HEADERS = Set.of("user-agent", "accept", "accept-language");
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ajdm-store").factory());
    private final FileChannel ownerChannel;
    private final FileLock ownerLock;
    private Connection connection;
    private boolean closed;

    public SqliteDownloadStore(Path database) throws IOException {
        Path path = database.toAbsolutePath().normalize();
        Path parent = path.getParent();
        boolean newParent = !Files.exists(parent);
        Files.createDirectories(parent);
        if (newParent) privatePermissions(parent, "rwx------");
        if (Files.isSymbolicLink(path)) throw storeFailure("State database must not be a symbolic link", null);
        Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
        if (Files.isSymbolicLink(lockPath)) throw storeFailure("State lock must not be a symbolic link", null);
        FileChannel ownership = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            privatePermissions(lockPath, "rw-------");
            try { lock = ownership.tryLock(); }
            catch (OverlappingFileLockException e) { lock = null; }
        } catch (IOException | RuntimeException e) {
            try { ownership.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            writer.shutdownNow();
            throw e;
        }
        if (lock == null) {
            ownership.close(); writer.shutdownNow();
            throw new DownloadException(ErrorCode.STATE_IN_USE, "AJDM state is already open in another engine");
        }
        ownerChannel = ownership;
        ownerLock = lock;
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createFile(path);
            privatePermissions(path, "rw-------");
            call(() -> { initialize(path); return null; });
        } catch (IOException | RuntimeException e) {
            try { close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }
    private static void privatePermissions(Path path, String permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }
    private static boolean column(java.sql.Statement statement, String table, String name) throws SQLException {
        try (var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) if (name.equals(rows.getString("name"))) return true;
        }
        return false;
    }
    private void initialize(Path path) throws SQLException, IOException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            int version;
            try (var result = statement.executeQuery("PRAGMA user_version")) { version = result.next() ? result.getInt(1) : 0; }
            if (version > 10) throw new IOException("State schema is newer than this engine");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA fullfsync=ON");
            statement.execute("PRAGMA foreign_keys=ON");
            connection.setAutoCommit(false);
            try {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS download (
                      id TEXT PRIMARY KEY, source_url TEXT NOT NULL, target_path TEXT NOT NULL,
                      part_path TEXT NOT NULL, state TEXT NOT NULL, total_bytes INTEGER NOT NULL DEFAULT -1,
                      resolved_url TEXT, accept_ranges INTEGER NOT NULL DEFAULT 0, etag TEXT, last_modified TEXT,
                      bytes_done INTEGER NOT NULL DEFAULT 0, ready INTEGER NOT NULL DEFAULT 0,
                      actual_sha256 TEXT, error_code TEXT, connect_ns INTEGER NOT NULL,
                      request_ns INTEGER NOT NULL, stall_ns INTEGER NOT NULL, buffer_bytes INTEGER NOT NULL,
                      max_buffers INTEGER NOT NULL, window_bytes INTEGER NOT NULL, max_retries INTEGER NOT NULL,
                      retry_base_ns INTEGER NOT NULL, retry_max_ns INTEGER NOT NULL, expected_sha256 TEXT,
                      checkpoint_ns INTEGER NOT NULL, created_at INTEGER NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS completed_range (
                      download_id TEXT NOT NULL REFERENCES download(id) ON DELETE CASCADE,
                      start INTEGER NOT NULL CHECK(start >= 0), "end" INTEGER NOT NULL CHECK("end" > start),
                      PRIMARY KEY(download_id, start))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS download_header (
                      download_id TEXT NOT NULL REFERENCES download(id) ON DELETE CASCADE,
                      name TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(download_id, name))
                    """);
            if (version < 2) {
                statement.execute("ALTER TABLE download ADD COLUMN connections INTEGER NOT NULL DEFAULT 1");
                statement.execute("ALTER TABLE download ADD COLUMN min_segment_bytes INTEGER NOT NULL DEFAULT 1048576");
            }
            if (version < 3) statement.execute("ALTER TABLE download ADD COLUMN adaptive INTEGER NOT NULL DEFAULT 0");
            if (version < 4) {
                statement.execute("ALTER TABLE download ADD COLUMN conflict_policy TEXT NOT NULL DEFAULT 'ASK'");
                statement.execute("ALTER TABLE download ADD COLUMN suggested_filename TEXT");
                statement.execute("ALTER TABLE download ADD COLUMN content_type TEXT");
            }
            if (version < 5) statement.execute("ALTER TABLE download ADD COLUMN publication_intent INTEGER NOT NULL DEFAULT 0");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS media_origin (
                      download_id TEXT PRIMARY KEY REFERENCES download(id) ON DELETE CASCADE,
                      page_url TEXT NOT NULL, media_id TEXT NOT NULL, format_id TEXT NOT NULL,
                      ext TEXT NOT NULL, vcodec TEXT NOT NULL, acodec TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS media_job (
                      id TEXT PRIMARY KEY, video_id TEXT NOT NULL UNIQUE REFERENCES download(id),
                      audio_id TEXT NOT NULL UNIQUE REFERENCES download(id), target_path TEXT NOT NULL,
                      workspace TEXT NOT NULL, video_ext TEXT NOT NULL, audio_ext TEXT NOT NULL,
                      container TEXT NOT NULL, conflict_policy TEXT NOT NULL, state TEXT NOT NULL,
                      estimated_bytes INTEGER NOT NULL, approximate INTEGER NOT NULL, error_code TEXT,
                      video_hash TEXT, audio_hash TEXT, output_hash TEXT, output_bytes INTEGER NOT NULL,
                      created_at INTEGER NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS playlist_job (
                      id TEXT PRIMARY KEY,title TEXT NOT NULL,page_url TEXT NOT NULL,directory TEXT NOT NULL,
                      conflict_policy TEXT NOT NULL,connections INTEGER NOT NULL,modern INTEGER NOT NULL,
                      state TEXT NOT NULL,error_code TEXT,next_extraction_at INTEGER NOT NULL,created_at INTEGER NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS playlist_item (
                      playlist_id TEXT NOT NULL REFERENCES playlist_job(id),item_index INTEGER NOT NULL,
                      media_id TEXT NOT NULL,title TEXT NOT NULL,duration REAL,page_url TEXT,preset TEXT,format_id TEXT,
                      audio_id TEXT,state TEXT NOT NULL,child_id TEXT UNIQUE,error_code TEXT,
                      PRIMARY KEY(playlist_id,item_index))
                    """);
            if (version < 9) statement.execute("ALTER TABLE download ADD COLUMN initial_connections INTEGER NOT NULL DEFAULT 2");
            statement.execute("CREATE TABLE IF NOT EXISTS host_profile (profile_key TEXT PRIMARY KEY, connections INTEGER NOT NULL CHECK(connections BETWEEN 1 AND 32), bytes_per_second REAL NOT NULL CHECK(bytes_per_second > 0), learned_at INTEGER NOT NULL)");
            if (version < 10 && !column(statement, "media_origin", "delivery")) {
                statement.execute("ALTER TABLE media_origin ADD COLUMN delivery TEXT NOT NULL DEFAULT 'file' CHECK(delivery IN ('file','hls'))");
            }
            statement.execute("PRAGMA user_version=10");
            statement.execute("UPDATE download SET state='PAUSED' WHERE state IN ('PROBING','DOWNLOADING','VERIFYING','MERGING')");
            connection.commit();
            } catch (SQLException e) { connection.rollback(); throw e; }
            finally { connection.setAutoCommit(true); }
        }
    }

    /** Optional performance hints; expiry and clock rollback invalidate them. */
    public Optional<HostProfile> hostProfile(String key, long now) throws IOException {
        if (key == null) return Optional.empty();
        if (!key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid host key");
        return call(() -> {
            try (var statement = connection.prepareStatement("SELECT connections,bytes_per_second,learned_at FROM host_profile WHERE profile_key=? AND learned_at<=? AND learned_at>=?")) {
                statement.setString(1, key); statement.setLong(2, now); statement.setLong(3, now - Duration.ofDays(30).toMillis());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    try { return Optional.of(new HostProfile(rows.getInt(1), rows.getDouble(2), rows.getLong(3))); }
                    catch (IllegalArgumentException ignored) { return Optional.empty(); }
                }
            }
        });
    }
    public void saveHostProfile(String key, HostProfile profile) throws IOException {
        if (key == null) return;
        if (!key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid host key");
        call(() -> transaction(() -> {
            try (var statement = connection.prepareStatement("INSERT INTO host_profile VALUES(?,?,?,?) ON CONFLICT(profile_key) DO UPDATE SET connections=excluded.connections,bytes_per_second=excluded.bytes_per_second,learned_at=excluded.learned_at")) {
                statement.setString(1, key); statement.setInt(2, profile.connections()); statement.setDouble(3, profile.bytesPerSecond()); statement.setLong(4, profile.learnedAtMillis()); statement.executeUpdate();
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM host_profile WHERE profile_key NOT IN (SELECT profile_key FROM host_profile ORDER BY learned_at DESC,profile_key LIMIT 512)");
            }
            return null;
        }));
    }
    @Override public void create(TransferRequest request, Path partial) throws IOException {
        create(request, partial, null);
    }
    @Override public void create(TransferRequest request, Path partial, MediaOrigin origin) throws IOException {
        call(() -> transaction(() -> { insertDownload(request, partial, origin); return null; }));
    }
    private void insertDownload(TransferRequest request, Path partial, MediaOrigin origin) throws SQLException {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO download(id,source_url,target_path,part_path,state,connect_ns,request_ns,stall_ns,
                    buffer_bytes,max_buffers,window_bytes,max_retries,retry_base_ns,retry_max_ns,expected_sha256,
                    checkpoint_ns,created_at,connections,min_segment_bytes,adaptive,conflict_policy,initial_connections) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                var options = request.options();
                statement.setString(1, request.id().toString()); statement.setString(2, request.uri().toString());
                statement.setString(3, request.target().toString()); statement.setString(4, partial.toString());
                statement.setString(5, DownloadState.QUEUED.name());
                statement.setLong(6, options.connectTimeout().toNanos()); statement.setLong(7, options.requestTimeout().toNanos());
                statement.setLong(8, options.stallTimeout().toNanos()); statement.setInt(9, options.bufferBytes());
                statement.setInt(10, options.maxInflightBuffers()); statement.setLong(11, options.windowBytes());
                statement.setInt(12, options.maxRetries()); statement.setLong(13, options.retryBaseDelay().toNanos());
                statement.setLong(14, options.retryMaxDelay().toNanos()); statement.setString(15, options.expectedSha256());
                statement.setLong(16, options.checkpointInterval().toNanos()); statement.setLong(17, System.currentTimeMillis());
                statement.setInt(18, options.connections()); statement.setLong(19, options.minSegmentBytes()); statement.setBoolean(20, options.adaptive());
                statement.setString(21, request.conflictPolicy().name()); statement.setInt(22, options.initialConnections());
                statement.executeUpdate();
            }
            if (origin != null) {
                try (var statement = connection.prepareStatement("INSERT INTO media_origin(download_id,page_url,media_id,format_id,ext,vcodec,acodec,delivery) VALUES(?,?,?,?,?,?,?,?)")) {
                    statement.setString(1, request.id().toString()); statement.setString(2, origin.pageUri().toString());
                    statement.setString(3, origin.mediaId()); statement.setString(4, origin.formatId()); statement.setString(5, origin.ext());
                    statement.setString(6, origin.vcodec()); statement.setString(7, origin.acodec());
                    statement.setString(8, origin.hls() ? "hls" : "file"); statement.executeUpdate();
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO download_header(download_id,name,value) VALUES(?,?,?)")) {
                for (var header : request.headers().entrySet()) {
                    if (!SAFE_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) continue;
                    statement.setString(1, request.id().toString()); statement.setString(2, header.getKey());
                    statement.setString(3, header.getValue()); statement.addBatch();
                }
                statement.executeBatch();
            }
    }
    /** Parent and both children are committed before any worker can start. */
    public void createMediaJob(MediaJob job, List<TransferRequest> requests, List<Path> partials, List<MediaOrigin> origins) throws IOException {
        if (requests.size() != 2 || partials.size() != 2 || origins.size() != 2
                || !requests.getFirst().id().equals(job.videoId()) || !requests.getLast().id().equals(job.audioId())
                || !requests.getFirst().target().equals(job.videoPath()) || !requests.getLast().target().equals(job.audioPath())) throw new IllegalArgumentException("Invalid child plan");
        call(() -> transaction(() -> {
            for (int i = 0; i < 2; i++) insertDownload(requests.get(i), partials.get(i), origins.get(i));
            try (var statement = connection.prepareStatement("INSERT INTO media_job VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                bindJob(statement, job); statement.setLong(18, System.currentTimeMillis()); statement.executeUpdate();
            }
            return null;
        }));
    }
    private static void bindJob(PreparedStatement statement, MediaJob job) throws SQLException {
        statement.setString(1, job.id().toString()); statement.setString(2, job.videoId().toString()); statement.setString(3, job.audioId().toString());
        statement.setString(4, job.target().toString()); statement.setString(5, job.workspace().toString()); statement.setString(6, job.videoExt());
        statement.setString(7, job.audioExt()); statement.setString(8, job.container()); statement.setString(9, job.conflict().name()); statement.setString(10, job.state().name());
        statement.setLong(11, job.estimatedBytes()); statement.setBoolean(12, job.approximateSize()); statement.setString(13, job.error() == null ? null : job.error().name());
        statement.setString(14, job.videoHash()); statement.setString(15, job.audioHash()); statement.setString(16, job.outputHash()); statement.setLong(17, job.outputBytes());
    }
    public void saveMediaJob(MediaJob job) throws IOException {
        call(() -> {
            try (var statement = connection.prepareStatement("UPDATE media_job SET target_path=?,conflict_policy=?,state=?,error_code=?,video_hash=?,audio_hash=?,output_hash=?,output_bytes=? WHERE id=?")) {
                statement.setString(1, job.target().toString()); statement.setString(2, job.conflict().name()); statement.setString(3, job.state().name());
                statement.setString(4, job.error() == null ? null : job.error().name()); statement.setString(5, job.videoHash()); statement.setString(6, job.audioHash());
                statement.setString(7, job.outputHash()); statement.setLong(8, job.outputBytes()); statement.setString(9, job.id().toString());
                if (statement.executeUpdate() != 1) throw new SQLException("Unknown media job");
            } return null;
        });
    }
    public List<MediaJob> mediaJobs() throws IOException {
        return call(() -> {
            var jobs = new ArrayList<MediaJob>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM media_job ORDER BY created_at,id")) {
                while (rows.next()) {
                    String error = rows.getString("error_code");
                    jobs.add(new MediaJob(UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("video_id")), UUID.fromString(rows.getString("audio_id")),
                            Path.of(rows.getString("target_path")), Path.of(rows.getString("workspace")), rows.getString("video_ext"), rows.getString("audio_ext"), rows.getString("container"),
                            ConflictPolicy.valueOf(rows.getString("conflict_policy")), DownloadState.valueOf(rows.getString("state")), rows.getLong("estimated_bytes"), rows.getBoolean("approximate"),
                            error == null ? null : ErrorCode.valueOf(error), rows.getString("video_hash"), rows.getString("audio_hash"), rows.getString("output_hash"), rows.getLong("output_bytes")));
                }
            } return List.copyOf(jobs);
        });
    }
    public void createPlaylist(PlaylistJob job, List<PlaylistItem> items) throws IOException {
        if (items.isEmpty() || items.size() > 1000 || items.stream().anyMatch(i -> !i.playlistId().equals(job.id()))) throw new IllegalArgumentException("Invalid playlist items");
        call(() -> transaction(() -> {
            try (var statement = connection.prepareStatement("INSERT INTO playlist_job VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, job.id().toString()); statement.setString(2, job.title()); statement.setString(3, job.page().toString());
                statement.setString(4, job.directory().toString()); statement.setString(5, job.conflict().name()); statement.setInt(6, job.connections());
                statement.setBoolean(7, job.modernCodecs()); statement.setString(8, job.state().name()); statement.setString(9, job.error() == null ? null : job.error().name());
                statement.setLong(10, job.nextExtractionAt()); statement.setLong(11, System.currentTimeMillis()); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO playlist_item VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (var item : items) {
                    statement.setString(1, item.playlistId().toString()); statement.setInt(2, item.index()); statement.setString(3, item.mediaId()); statement.setString(4, item.title());
                    if (item.duration() == null) statement.setNull(5, Types.REAL); else statement.setDouble(5, item.duration());
                    statement.setString(6, item.page() == null ? null : item.page().toString()); statement.setString(7, item.preset()); statement.setString(8, item.formatId());
                    statement.setString(9, item.audioId()); statement.setString(10, item.state().name()); statement.setString(11, item.childId() == null ? null : item.childId().toString());
                    statement.setString(12, item.error() == null ? null : item.error().name()); statement.addBatch();
                } statement.executeBatch();
            } return null;
        }));
    }
    public void savePlaylist(PlaylistJob job) throws IOException {
        call(() -> {
            try (var statement = connection.prepareStatement("UPDATE playlist_job SET state=?,error_code=?,next_extraction_at=? WHERE id=?")) {
                statement.setString(1, job.state().name()); statement.setString(2, job.error() == null ? null : job.error().name()); statement.setLong(3, job.nextExtractionAt());
                statement.setString(4, job.id().toString()); if (statement.executeUpdate() != 1) throw new SQLException("Unknown playlist");
            } return null;
        });
    }
    public void savePlaylistItem(PlaylistItem item) throws IOException {
        call(() -> {
            try (var statement = connection.prepareStatement("UPDATE playlist_item SET state=?,child_id=?,error_code=? WHERE playlist_id=? AND item_index=?")) {
                statement.setString(1, item.state().name()); statement.setString(2, item.childId() == null ? null : item.childId().toString());
                statement.setString(3, item.error() == null ? null : item.error().name()); statement.setString(4, item.playlistId().toString()); statement.setInt(5, item.index());
                if (statement.executeUpdate() != 1) throw new SQLException("Unknown playlist item");
            } return null;
        });
    }
    public List<PlaylistJob> playlists() throws IOException {
        return call(() -> {
            var jobs = new ArrayList<PlaylistJob>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM playlist_job ORDER BY created_at,id")) {
                while (rows.next()) { String error = rows.getString("error_code");
                    jobs.add(new PlaylistJob(UUID.fromString(rows.getString("id")), rows.getString("title"), URI.create(rows.getString("page_url")), Path.of(rows.getString("directory")),
                            ConflictPolicy.valueOf(rows.getString("conflict_policy")), rows.getInt("connections"), rows.getBoolean("modern"), DownloadState.valueOf(rows.getString("state")),
                            error == null ? null : ErrorCode.valueOf(error), rows.getLong("next_extraction_at")));
                }
            } return List.copyOf(jobs);
        });
    }
    public List<PlaylistItem> playlistItems(UUID id) throws IOException {
        return call(() -> {
            var items = new ArrayList<PlaylistItem>();
            try (var statement = connection.prepareStatement("SELECT * FROM playlist_item WHERE playlist_id=? ORDER BY item_index")) {
                statement.setString(1, id.toString());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) { String page = rows.getString("page_url"), child = rows.getString("child_id"), error = rows.getString("error_code");
                        double duration = rows.getDouble("duration"); Double value = rows.wasNull() ? null : duration;
                        items.add(new PlaylistItem(id, rows.getInt("item_index"), rows.getString("media_id"), rows.getString("title"), value, page == null ? null : URI.create(page),
                                rows.getString("preset"), rows.getString("format_id"), rows.getString("audio_id"), PlaylistItem.State.valueOf(rows.getString("state")),
                                child == null ? null : UUID.fromString(child), error == null ? null : ErrorCode.valueOf(error)));
                    }
                }
            } return List.copyOf(items);
        });
    }
    @Override public Optional<MediaOrigin> mediaOrigin(UUID id) throws IOException {
        return call(() -> {
            try (var statement = connection.prepareStatement("SELECT page_url,media_id,format_id,ext,vcodec,acodec,delivery FROM media_origin WHERE download_id=?")) {
                statement.setString(1, id.toString());
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(new MediaOrigin(URI.create(rows.getString(1)), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getString(6), "hls".equals(rows.getString(7)))) : Optional.empty();
                }
            }
        });
    }
    @Override public DownloadRecord get(UUID id) throws IOException { return call(() -> read(id)); }
    /** Called after the engine/coordinators exclude workers. Related metadata is removed in one commit. Files are untouched. */
    public Set<UUID> removeStopped(UUID id) throws IOException {
        return call(() -> transaction(() -> {
            var removed = new HashSet<UUID>(); removeGraph(id, removed, new HashSet<>()); return Set.copyOf(removed);
        }));
    }
    private void removeGraph(UUID id, Set<UUID> removed, Set<UUID> visited) throws SQLException, IOException {
        if (!visited.add(id)) throw new IOException("Invalid removal graph");
        if (present("SELECT 1 FROM media_job WHERE video_id=? OR audio_id=?", id, id)) throw new IOException("Remove the media parent, not its hidden child");
        if (present("SELECT 1 FROM playlist_job WHERE id=?", id)) {
            var children = new ArrayList<UUID>();
            try (var statement = connection.prepareStatement("SELECT child_id FROM playlist_item WHERE playlist_id=? AND child_id IS NOT NULL")) {
                statement.setString(1, id.toString()); try (var rows = statement.executeQuery()) { while (rows.next()) children.add(UUID.fromString(rows.getString(1))); }
            }
            executeRemoval("DELETE FROM playlist_item WHERE playlist_id=?", id);
            for (UUID child : children) {
                if (present("SELECT 1 FROM playlist_job WHERE id=?", child)) throw new IOException("Nested playlist child is not a download");
                if (present("SELECT 1 FROM download WHERE id=?", child) || present("SELECT 1 FROM media_job WHERE id=?", child)) removeGraph(child, removed, visited);
            }
            executeRemoval("DELETE FROM playlist_job WHERE id=?", id); return;
        }
        var children = new ArrayList<UUID>();
        try (var statement = connection.prepareStatement("SELECT video_id,audio_id FROM media_job WHERE id=?")) {
            statement.setString(1, id.toString()); try (var rows = statement.executeQuery()) {
                if (rows.next()) { children.add(UUID.fromString(rows.getString(1))); children.add(UUID.fromString(rows.getString(2))); }
            }
        }
        if (children.isEmpty()) children.add(id);
        for (UUID child : children) {
            if (!Set.of(DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELED, DownloadState.COMPLETED).contains(read(child).state()))
                throw new IOException("Download is still running");
        }
        try (var statement = connection.prepareStatement("UPDATE playlist_item SET child_id=NULL,state='CANCELED',error_code='CANCELED' WHERE child_id=?")) {
            statement.setString(1, id.toString()); statement.executeUpdate();
        }
        executeRemoval("DELETE FROM media_job WHERE id=?", id);
        for (UUID child : children) { executeRemoval("DELETE FROM download WHERE id=?", child); removed.add(child); }
    }
    private boolean present(String query, UUID... ids) throws SQLException {
        try (var statement = connection.prepareStatement(query)) {
            for (int i = 0; i < ids.length; i++) statement.setString(i + 1, ids[i].toString());
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    private void executeRemoval(String query, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement(query)) { statement.setString(1, id.toString()); statement.executeUpdate(); }
    }
    /** Atomically configure stopped children without changing coverage, identity or publication metadata. */
    public void connections(List<UUID> ids, int count) throws IOException {
        TransferOptions.defaults().withConnectionCount(count);
        var selected = List.copyOf(ids);
        call(() -> transaction(() -> {
            for (UUID id : selected) {
                var state = read(id).state();
                if (!Set.of(DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.FAILED).contains(state)) throw new IOException("Download must be stopped before configuring connections");
            }
            try (var statement = connection.prepareStatement("UPDATE download SET connections=? WHERE id=?")) {
                for (UUID id : selected) {
                    statement.setInt(1, count); statement.setString(2, id.toString());
                    if (statement.executeUpdate() != 1) throw new IOException("Unknown download ID");
                }
            }
            return null;
        }));
    }
    @Override public boolean exists(UUID id) throws IOException {
        return call(() -> {
            try (var statement = connection.prepareStatement("SELECT 1 FROM download WHERE id=?")) {
                statement.setString(1, id.toString());
                try (var rows = statement.executeQuery()) { return rows.next(); }
            }
        });
    }
    @Override public List<DownloadRecord> list() throws IOException {
        return call(() -> {
            List<UUID> ids = new ArrayList<>();
            try (var statement = connection.createStatement(); var results = statement.executeQuery("SELECT id FROM download ORDER BY created_at,id")) {
                while (results.next()) ids.add(UUID.fromString(results.getString(1)));
            }
            List<DownloadRecord> rows = new ArrayList<>();
            for (UUID id : ids) rows.add(read(id));
            return List.copyOf(rows);
        });
    }
    private DownloadRecord read(UUID id) throws SQLException, IOException {
        Map<String, String> headers = new HashMap<>();
        try (var statement = connection.prepareStatement("SELECT name,value FROM download_header WHERE download_id=?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) headers.put(rows.getString(1), rows.getString(2));
            }
        }
        List<ByteRange> ranges = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT start,\"end\" FROM completed_range WHERE download_id=? ORDER BY start")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) { while (rows.next()) ranges.add(new ByteRange(rows.getLong(1), rows.getLong(2))); }
        }
        try (var statement = connection.prepareStatement("SELECT * FROM download WHERE id=?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IOException("Unknown download ID");
                var options = new TransferOptions(Duration.ofNanos(rows.getLong("connect_ns")), Duration.ofNanos(rows.getLong("request_ns")),
                        Duration.ofNanos(rows.getLong("stall_ns")), rows.getInt("buffer_bytes"), rows.getInt("max_buffers"),
                        rows.getLong("window_bytes"), rows.getInt("max_retries"), Duration.ofNanos(rows.getLong("retry_base_ns")),
                        Duration.ofNanos(rows.getLong("retry_max_ns")), rows.getString("expected_sha256"), Duration.ofNanos(rows.getLong("checkpoint_ns")),
                        rows.getInt("connections"), rows.getLong("min_segment_bytes"), rows.getBoolean("adaptive"), rows.getInt("initial_connections"));
                var request = new TransferRequest(id, URI.create(rows.getString("source_url")), Path.of(rows.getString("target_path")), headers, options,
                        ConflictPolicy.valueOf(rows.getString("conflict_policy")));
                String resolved = rows.getString("resolved_url");
                ProbeResult probe = resolved == null ? null : new ProbeResult(URI.create(resolved), rows.getLong("total_bytes"),
                        rows.getBoolean("accept_ranges"), rows.getString("etag"), rows.getString("last_modified"),
                        rows.getString("suggested_filename"), rows.getString("content_type"));
                String error = rows.getString("error_code");
                var record = new DownloadRecord(request, Path.of(rows.getString("part_path")), DownloadState.valueOf(rows.getString("state")),
                        probe, ranges, rows.getBoolean("ready"), rows.getString("actual_sha256"), error == null ? null : ErrorCode.valueOf(error),
                        rows.getBoolean("publication_intent"));
                if (record.publicationIntent() && (!record.readyToPublish() || record.actualSha256() == null
                        || !record.actualSha256().matches("[0-9a-f]{64}"))) throw new IOException("Invalid publication journal");
                if (new RangeSet(ranges).coveredBytes() != rows.getLong("bytes_done")) throw new IOException("Inconsistent stored coverage");
                if (probe != null && probe.contentLength() >= 0) new RangeSet(ranges).gaps(probe.contentLength());
                return record;
            }
        }
    }
    @Override public void describe(UUID id, ProbeResult probe) throws IOException {
        call(() -> transaction(() -> {
            try (var statement = connection.prepareStatement("UPDATE download SET resolved_url=?,total_bytes=?,accept_ranges=?,etag=?,last_modified=?,suggested_filename=?,content_type=? WHERE id=?")) {
                statement.setString(1, probe.finalUri().toString()); statement.setLong(2, probe.contentLength());
                statement.setBoolean(3, probe.acceptRanges()); statement.setString(4, probe.etag());
                statement.setString(5, probe.lastModified()); statement.setString(6, probe.filename());
                statement.setString(7, probe.contentType()); statement.setString(8, id.toString());
                if (statement.executeUpdate() != 1) throw new IOException("Unknown download ID");
            }
            return null;
        }));
    }
    /** Commit source URL, validated probe, and only restart-safe headers together with existing coverage. */
    @Override public void replaceSource(UUID id, DownloadSource source, ProbeResult probe) throws IOException {
        call(() -> transaction(() -> {
            DownloadRecord previous = read(id);
            SourceIdentity.requireSame(previous.probe(), probe, previous.durableBytes());
            if (("https".equalsIgnoreCase(previous.request().uri().getScheme())
                    || previous.probe() != null && "https".equalsIgnoreCase(previous.probe().finalUri().getScheme()))
                    && !"https".equalsIgnoreCase(source.uri().getScheme())) {
                throw new DownloadException(ErrorCode.INVALID_URL, "Source refresh cannot downgrade HTTPS");
            }
            try (var statement = connection.prepareStatement("UPDATE download SET source_url=?,resolved_url=?,total_bytes=?,accept_ranges=?,etag=?,last_modified=?,suggested_filename=?,content_type=? WHERE id=?")) {
                statement.setString(1, source.uri().toString()); statement.setString(2, probe.finalUri().toString());
                statement.setLong(3, probe.contentLength()); statement.setBoolean(4, probe.acceptRanges());
                statement.setString(5, probe.etag()); statement.setString(6, probe.lastModified());
                statement.setString(7, probe.filename()); statement.setString(8, probe.contentType()); statement.setString(9, id.toString());
                if (statement.executeUpdate() != 1) throw new IOException("Unknown download ID");
            }
            try (var statement = connection.prepareStatement("DELETE FROM download_header WHERE download_id=?")) {
                statement.setString(1, id.toString()); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO download_header(download_id,name,value) VALUES(?,?,?)")) {
                for (var header : source.headers().entrySet()) {
                    if (!SAFE_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) continue;
                    statement.setString(1, id.toString()); statement.setString(2, header.getKey());
                    statement.setString(3, header.getValue()); statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        }));
    }
    @Override public void replaceStreamSource(UUID id, DownloadSource source) throws IOException {
        call(() -> transaction(() -> {
            DownloadRecord previous = read(id);
            try (var statement = connection.prepareStatement("SELECT 1 FROM media_origin WHERE download_id=? AND delivery='hls'")) {
                statement.setString(1, id.toString());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new DownloadException(ErrorCode.REFRESH_UNSUPPORTED, "Not a stream download");
                }
            }
            if ("https".equalsIgnoreCase(previous.request().uri().getScheme()) && !"https".equalsIgnoreCase(source.uri().getScheme())) {
                throw new DownloadException(ErrorCode.INVALID_URL, "Source refresh cannot downgrade HTTPS");
            }
            try (var statement = connection.prepareStatement("UPDATE download SET source_url=? WHERE id=?")) {
                statement.setString(1, source.uri().toString()); statement.setString(2, id.toString());
                if (statement.executeUpdate() != 1) throw new IOException("Unknown download ID");
            }
            try (var statement = connection.prepareStatement("DELETE FROM download_header WHERE download_id=?")) {
                statement.setString(1, id.toString()); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO download_header(download_id,name,value) VALUES(?,?,?)")) {
                for (var header : source.headers().entrySet()) {
                    if (!SAFE_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) continue;
                    statement.setString(1, id.toString()); statement.setString(2, header.getKey());
                    statement.setString(3, header.getValue()); statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        }));
    }
    @Override public void checkpoint(UUID id, List<ByteRange> coverage, DownloadState state) throws IOException {
        checkpoint(id, coverage, state, null);
    }
    @Override public void checkpoint(UUID id, List<ByteRange> coverage, DownloadState state, ErrorCode error) throws IOException {
        RangeSet ranges = new RangeSet(coverage);
        call(() -> transaction(() -> {
            DownloadRecord previous = read(id);
            if (previous.probe() != null && previous.probe().contentLength() >= 0) ranges.gaps(previous.probe().contentLength());
            changeState(id, state, error);
            try (var statement = connection.prepareStatement("DELETE FROM completed_range WHERE download_id=?")) {
                statement.setString(1, id.toString()); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO completed_range(download_id,start,\"end\") VALUES(?,?,?)")) {
                for (ByteRange range : ranges.ranges()) {
                    statement.setString(1, id.toString()); statement.setLong(2, range.start()); statement.setLong(3, range.endExclusive()); statement.addBatch();
                }
                statement.executeBatch();
            }
            try (var statement = connection.prepareStatement("UPDATE download SET bytes_done=?,ready=0,actual_sha256=NULL,publication_intent=0 WHERE id=?")) {
                statement.setLong(1, ranges.coveredBytes()); statement.setString(2, id.toString()); statement.executeUpdate();
            }
            return null;
        }));
    }
    @Override public void state(UUID id, DownloadState state, ErrorCode error) throws IOException {
        call(() -> transaction(() -> { changeState(id, state, error); return null; }));
    }
    private void changeState(UUID id, DownloadState to, ErrorCode error) throws SQLException, IOException {
        DownloadState from;
        try (var statement = connection.prepareStatement("SELECT state FROM download WHERE id=?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IOException("Unknown download ID");
                from = DownloadState.valueOf(rows.getString(1));
            }
        }
        boolean allowed = from == to || switch (from) {
            case QUEUED -> Set.of(DownloadState.PROBING, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELED).contains(to);
            case PROBING -> Set.of(DownloadState.DOWNLOADING, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELED).contains(to);
            case DOWNLOADING -> Set.of(DownloadState.VERIFYING, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELED).contains(to);
            case VERIFYING -> Set.of(DownloadState.COMPLETED, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELED).contains(to);
            case PAUSED, FAILED -> Set.of(DownloadState.PROBING, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELED).contains(to);
            default -> false;
        };
        if (!allowed) throw new IOException("Invalid persisted state transition: " + from + " to " + to);
        try (var statement = connection.prepareStatement("UPDATE download SET state=?,error_code=? WHERE id=?")) {
            statement.setString(1, to.name()); statement.setString(2, error == null ? null : error.name()); statement.setString(3, id.toString()); statement.executeUpdate();
        }
    }
    @Override public void ready(UUID id, String actualSha256) throws IOException {
        call(() -> transaction(() -> {
            DownloadRecord record = read(id);
            if (record.state() != DownloadState.VERIFYING || record.probe() == null
                    || record.probe().contentLength() >= 0 && record.durableBytes() != record.probe().contentLength()) {
                throw new IOException("Download is not ready for publication");
            }
            try (var statement = connection.prepareStatement("UPDATE download SET ready=1,actual_sha256=?,publication_intent=0 WHERE id=?")) {
                statement.setString(1, actualSha256); statement.setString(2, id.toString()); statement.executeUpdate();
            }
            return null;
        }));
    }
    @Override public void relocateCompletedStream(UUID id, Path target) throws IOException {
        call(() -> transaction(() -> {
            DownloadRecord previous = read(id);
            var next = previous.request().withTarget(target);
            if (previous.state() != DownloadState.COMPLETED || !next.target().getParent().equals(previous.request().target().getParent())) {
                throw new IOException("Relocation is not allowed");
            }
            try (var statement = connection.prepareStatement("SELECT 1 FROM media_origin WHERE download_id=? AND delivery='hls'")) {
                statement.setString(1, id.toString());
                try (var rows = statement.executeQuery()) { if (!rows.next()) throw new IOException("Not a stream download"); }
            }
            // The recorded hash described the transport stream, not the remuxed file.
            try (var statement = connection.prepareStatement("UPDATE download SET target_path=?,actual_sha256=NULL WHERE id=?")) {
                statement.setString(1, next.target().toString()); statement.setString(2, id.toString());
                if (statement.executeUpdate() != 1) throw new IOException("Unknown download ID");
            }
            return null;
        }));
    }
    @Override public void destination(UUID id, Path target, ConflictPolicy policy) throws IOException {
        call(() -> transaction(() -> {
            DownloadRecord previous = read(id);
            var next = previous.request().withTarget(target).withConflictPolicy(policy);
            if (!next.target().getParent().equals(previous.request().target().getParent())
                    || previous.state() == DownloadState.COMPLETED || previous.state() == DownloadState.CANCELED) {
                throw new IOException("Destination change is not allowed");
            }
            try (var statement = connection.prepareStatement("UPDATE download SET target_path=?,conflict_policy=?,publication_intent=0 WHERE id=?")) {
                statement.setString(1, next.target().toString()); statement.setString(2, policy.name()); statement.setString(3, id.toString());
                if (statement.executeUpdate() != 1) throw new IOException("Unknown download ID");
            }
            return null;
        }));
    }
    @Override public void publicationIntent(UUID id, String actualSha256) throws IOException {
        call(() -> transaction(() -> {
            DownloadRecord record = read(id);
            if (record.state() != DownloadState.VERIFYING || !record.readyToPublish()
                    || record.request().conflictPolicy() != ConflictPolicy.OVERWRITE
                    || record.probe() == null || record.probe().contentLength() < 0
                    || record.durableBytes() != record.probe().contentLength()
                    || record.actualSha256() != null && !record.actualSha256().equals(actualSha256)
                    || actualSha256 == null || !actualSha256.matches("[0-9a-f]{64}")) {
                throw new IOException("Download is not ready for atomic replacement");
            }
            try (var statement = connection.prepareStatement("UPDATE download SET publication_intent=1,actual_sha256=? WHERE id=?")) {
                statement.setString(1, actualSha256); statement.setString(2, id.toString()); statement.executeUpdate();
            }
            return null;
        }));
    }
    private <T> T transaction(Callable<T> operation) throws Exception {
        connection.setAutoCommit(false);
        try { T result = operation.call(); connection.commit(); return result; }
        catch (Exception e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(true); }
    }
    private synchronized <T> T call(Callable<T> operation) throws IOException {
        if (closed) throw storeFailure("State store is closed", null);
        Future<T> future = writer.submit(operation);
        boolean interrupted = Thread.interrupted();
        try {
            for (;;) {
                try { return future.get(); }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) { throw storeFailure("Could not read or save AJDM state", e.getCause()); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static DownloadException storeFailure(String message, Throwable cause) {
        return new DownloadException(ErrorCode.STATE_STORE_ERROR, message, cause);
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        try { call(() -> { if (connection != null) connection.close(); return null; }); }
        catch (IOException e) { failure = e; }
        finally {
            closed = true; writer.shutdown(); writer.close();
            ownerLock.release(); ownerChannel.close();
        }
        if (failure != null) throw failure;
    }
}
