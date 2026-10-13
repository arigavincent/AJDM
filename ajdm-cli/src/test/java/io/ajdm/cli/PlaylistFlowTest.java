package io.ajdm.cli;

import com.google.gson.*;
import io.ajdm.core.*;
import io.ajdm.ipc.*;
import io.ajdm.media.*;
import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@SuppressWarnings("try")
class PlaylistFlowTest {
    @TempDir Path temp;
    Path db() { return temp.resolve("state/ajdm.db"); }
    Path files() { return temp.resolve("files"); }
    Path socket() { return temp.resolve("ipc/ajdm.sock"); }
    static JsonObject flat(int count, boolean unavailable) {
        var root = new JsonObject(); root.addProperty("_type", "playlist"); root.addProperty("id", "list"); root.addProperty("title", "List\u001b[31m"); var entries = new JsonArray();
        for (int i = 1; i <= count; i++) {
            if (unavailable && i == 2) { entries.add(JsonNull.INSTANCE); continue; }
            var row = new JsonObject(); row.addProperty("id", "v" + i); row.addProperty("title", "Item " + i); row.addProperty("url", "https://page.test/item/" + i + "?token=secret");
            if (unavailable && i == 3) row.addProperty("availability", "private"); entries.add(row);
        } root.add("entries", entries); return root;
    }
    static JsonObject full(String url, int index, boolean pair) {
        var root = new JsonObject(); root.addProperty("id", "v" + index); root.addProperty("title", "Item " + index); var formats = new JsonArray();
        var headers = new JsonObject(); headers.addProperty("Cookie", "sid=secret"); headers.addProperty("User-Agent", "AJDM-extractor-fixture"); root.add("http_headers", headers);
        for (boolean video : new boolean[]{true, false}) {
            var f = new JsonObject(); f.addProperty("format_id", video ? "video" : "audio"); f.addProperty("ext", "mp4"); f.addProperty("protocol", "https"); f.addProperty("url", url);
            f.addProperty("vcodec", video ? "avc1" : "none"); f.addProperty("acodec", video && pair ? "none" : "mp4a"); f.addProperty("height", video ? 1080 : 0); f.addProperty("abr", 128); formats.add(f);
        } root.add("formats", formats); return root;
    }
    MediaExtractor extractor(MediaHttpsFixture fixture, int count, boolean unavailable, AtomicInteger calls) {
        return new MediaExtractor() {
            @Override public PlaylistInfo playlist(URI page, java.util.function.BooleanSupplier canceled) throws IOException { return new PlaylistParser().parse(flat(count, unavailable).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), page); }
            @Override public MediaInfo extract(URI page, java.util.function.BooleanSupplier canceled) throws IOException {
                calls.incrementAndGet(); int index = Integer.parseInt(page.getPath().substring(page.getPath().lastIndexOf('/') + 1));
                if (unavailable && index == 4) throw new DownloadException(ErrorCode.EXTRACTION_FAILED, "secret");
                return new MediaParser().parse(full(fixture.url(!fixture.expired), index, index == 5).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), page);
            }
        };
    }
    MediaMuxer muxer() { return (v, ve, a, ae, out, c, cancel) -> Files.write(out, Files.readAllBytes(v), StandardOpenOption.CREATE_NEW); }
    CoreService.Row group(CoreService core, UUID id) throws Exception { return core.list().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(); }
    void complete(CoreService core, UUID id, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) { var row = group(core, id); if (row.state() == DownloadState.COMPLETED) return;
            assertFalse(Set.of(DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELED).contains(row.state()), row.toString()); Thread.sleep(50); }
        fail("Playlist did not complete: " + group(core, id));
    }
    UUID add(CoreService core, String items) throws Exception { return core.addPlaylist("https://page.test/list", items, new PlaylistSelection.Choice("P1080", null, null), Map.of(), files(), ConflictPolicy.RENAME, 1, true); }
    @Test void flatPagingChoicesAndSkippedReasonsUseSafeCliIpcAndAjdmPayloads() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0); var core = new CoreService(db(), extractor(fixture, 5, true, calls), muxer()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var p = new JsonObject(); p.addProperty("url", "https://page.test/list?secret=token"); p.addProperty("limit", 2);
            var listed = client.call("playlist", p); assertEquals(5, listed.get("totalEntries").getAsInt()); assertEquals(2, listed.getAsJsonArray("entries").size());
            assertEquals(0, calls.get()); assertEquals(0, fixture.oldRequests.get()); assertFalse(listed.toString().contains("secret"));
            var out = new StringWriter(); var err = new StringWriter();
            assertEquals(0, AjdmCli.execute(new String[]{"--socket", socket().toString(), "add-playlist", "https://page.test/list", "--items", "1-5", "--preset", "P1080", "--choice", "5=format:video+audio", "--directory", files().toString(), "--wait"}, new PrintWriter(out), new PrintWriter(err)), err.toString());
            UUID id = UUID.fromString(out.toString().lines().findFirst().orElseThrow()); var items = core.playlistItems(id);
            assertEquals(2, items.stream().filter(i -> i.state() == PlaylistItem.State.COMPLETED).count()); assertEquals(3, items.stream().filter(i -> i.state() == PlaylistItem.State.SKIPPED).count()); assertEquals(3, calls.get());
            assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("0001-Item 1.mp4"))); assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("0005-Item 5.mp4")));
            assertEquals(ErrorCode.AUTH_REQUIRED, items.get(2).error()); assertFalse(out.toString().contains("\u001b"));
            var q = new JsonObject(); q.addProperty("downloadId", id.toString()); assertFalse(client.call("playlistStatus", q).toString().contains("secret"));
            assertFalse(fixture.missingHeaders);
        }
    }
    @Test void pauseReopenKeepsCompletedEntriesAndResumesOnlyUnfinishedWork() throws Exception {
        AtomicInteger calls = new AtomicInteger(); UUID id;
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0)) {
            try (var core = new CoreService(db(), extractor(fixture, 3, false, calls), muxer())) {
                id = add(core, "all"); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (core.playlistItems(id).stream().noneMatch(i -> i.state() == PlaylistItem.State.COMPLETED) && System.nanoTime() < deadline) Thread.sleep(20);
                core.pause(id); assertEquals(DownloadState.PAUSED, group(core, id).state()); assertEquals(1, calls.get());
            }
            try (var core = new CoreService(db(), extractor(fixture, 3, false, calls), muxer())) {
                assertEquals(DownloadState.PAUSED, group(core, id).state()); core.resume(id, Map.of(), null); complete(core, id, 12); assertEquals(3, calls.get());
                for (int i = 1; i <= 3; i++) assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve(String.format("%04d-Item %d.mp4", i, i))));
            }
        }
    }
    @Test void entryPreviewAndInvalidSelectionsNeverDownloadOrCreateJobs() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0); var core = new CoreService(db(), extractor(fixture, 5, true, calls), muxer()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var p = new JsonObject(); p.addProperty("url", "https://page.test/list"); p.addProperty("index", 5); p.addProperty("formatId", "video"); p.addProperty("audioId", "audio");
            var preview = client.call("playlistFormats", p); assertTrue(preview.getAsJsonObject("selection").get("requiresMux").getAsBoolean()); assertFalse(preview.toString().contains("secret"));
            assertEquals(1, calls.get()); assertEquals(0, fixture.oldRequests.get());
            p.addProperty("index", 3); assertEquals(ErrorCode.AUTH_REQUIRED, assertThrows(DownloadException.class, () -> client.call("playlistFormats", p)).code()); assertEquals(1, calls.get());
            var invalid = new JsonObject(); invalid.addProperty("url", "https://page.test/list"); invalid.addProperty("items", "1"); var choices = new JsonArray(); var choice = new JsonObject(); choice.addProperty("index", 5); choice.addProperty("preset", "BEST"); choices.add(choice); invalid.add("choices", choices);
            assertThrows(DownloadException.class, () -> client.call("addPlaylist", invalid));
            choice.addProperty("index", 1); choices.add(choice.deepCopy()); assertThrows(DownloadException.class, () -> client.call("addPlaylist", invalid));
            var page = new JsonObject(); page.addProperty("url", "https://page.test/list"); page.addProperty("limit", 101); assertThrows(DownloadException.class, () -> client.call("playlist", page));
            assertTrue(core.list().isEmpty()); assertEquals(0, fixture.oldRequests.get());
        }
    }
    @Test void failedPayloadDoesNotAbortOtherItemsAndWaitReturnsFailure() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0)) {
            var base = extractor(fixture, 3, false, calls);
            MediaExtractor faulty = new MediaExtractor() {
                @Override public PlaylistInfo playlist(URI p, java.util.function.BooleanSupplier c) throws IOException, InterruptedException { return base.playlist(p, c); }
                @Override public MediaInfo extract(URI p, java.util.function.BooleanSupplier c) throws IOException, InterruptedException {
                    if (p.getPath().endsWith("/1")) return new MediaParser().parse(full(fixture.url(true).replace("/old?", "/invalid?"), 1, false).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), p);
                    return base.extract(p, c);
                }
            };
            try (var core = new CoreService(db(), faulty, muxer()); var server = new IpcServer(socket(), core)) {
                var out = new StringWriter(); var err = new StringWriter();
                assertEquals(6, AjdmCli.execute(new String[]{"--socket", socket().toString(), "add-playlist", "https://page.test/list", "--preset", "P1080", "--directory", files().toString(), "--wait"}, new PrintWriter(out), new PrintWriter(err)), err.toString());
                UUID id = UUID.fromString(out.toString().lines().findFirst().orElseThrow()); assertEquals(DownloadState.COMPLETED, group(core, id).state());
                assertEquals(PlaylistItem.State.FAILED, core.playlistItems(id).getFirst().state()); assertEquals(2, core.playlistItems(id).stream().filter(i -> i.state() == PlaylistItem.State.COMPLETED).count());
                assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("0002-Item 2.mp4"))); assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("0003-Item 3.mp4"))); assertFalse(err.toString().contains("secret"));
            }
        }
    }
    @Test void cancellationStopsExtractionAndDoesNotLaunchUnselectedEntries() throws Exception {
        AtomicInteger calls = new AtomicInteger(); CountDownLatch entered = new CountDownLatch(1), stopped = new CountDownLatch(1);
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0)) {
            MediaExtractor base = extractor(fixture, 3, false, calls);
            MediaExtractor slow = new MediaExtractor() {
                @Override public PlaylistInfo playlist(URI page, java.util.function.BooleanSupplier canceled) throws IOException, InterruptedException { return base.playlist(page, canceled); }
                @Override public MediaInfo extract(URI page, java.util.function.BooleanSupplier canceled) throws InterruptedException {
                    entered.countDown(); try { Thread.sleep(30_000); return null; } finally { stopped.countDown(); }
                }
            };
            try (var core = new CoreService(db(), slow, muxer())) {
                UUID id = add(core, "1,3"); assertTrue(entered.await(5, TimeUnit.SECONDS)); core.cancel(id); assertTrue(stopped.await(3, TimeUnit.SECONDS));
                assertEquals(DownloadState.CANCELED, group(core, id).state()); assertEquals(List.of(1,3), core.playlistItems(id).stream().map(PlaylistItem::index).toList());
                assertTrue(core.playlistItems(id).stream().allMatch(i -> i.state() == PlaylistItem.State.CANCELED)); assertEquals(0, fixture.oldRequests.get());
                assertThrows(IllegalStateException.class, () -> core.resume(id, Map.of(), null));
            }
        }
    }
    @Test void errorAfterPairedAdmissionPreservesIntentAndResumesExistingChild() throws Exception {
        var calls = new AtomicInteger(); UUID id; UUID childId;
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0)) {
            var base = extractor(fixture, 5, false, calls);
            try (var core = new CoreService(db(), base, muxer(), (job, boundary) -> {
                if (boundary == MediaJobObserver.Boundary.PREPARED) throw new DownloadException(ErrorCode.EXTRACTION_FAILED, "Private diagnostics");
            })) {
                id = add(core, "5"); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (group(core, id).state() == DownloadState.DOWNLOADING && System.nanoTime() < deadline) Thread.sleep(20);
                assertEquals(DownloadState.PAUSED, group(core, id).state()); assertEquals(PlaylistItem.State.PAUSED, core.playlistItems(id).getFirst().state());
                childId = core.playlistItems(id).getFirst().childId(); assertNotNull(childId); assertEquals(2, core.list().size()); assertEquals(0, fixture.oldRequests.get());
            }
            try (var core = new CoreService(db(), base, muxer())) {
                core.resume(id, Map.of(), null); complete(core, id, 10); assertEquals(childId, core.playlistItems(id).getFirst().childId()); assertEquals(1, calls.get());
                assertEquals(2, core.list().size()); assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("0005-Item 5.mp4")));
            }
        }
    }
    @Test void extractionOverlapNeverExceedsThreeAndStartsRespectTwoSecondSpacing() throws Exception {
        AtomicInteger calls = new AtomicInteger(), live = new AtomicInteger(), peak = new AtomicInteger(); var starts = new CopyOnWriteArrayList<Long>();
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0)) {
            MediaExtractor base = extractor(fixture, 4, false, calls);
            MediaExtractor slow = new MediaExtractor() {
                @Override public PlaylistInfo playlist(URI p, java.util.function.BooleanSupplier c) throws IOException, InterruptedException { return base.playlist(p,c); }
                @Override public MediaInfo extract(URI p, java.util.function.BooleanSupplier c) throws IOException, InterruptedException {
                    starts.add(System.nanoTime()); peak.accumulateAndGet(live.incrementAndGet(), Math::max);
                    try { Thread.sleep(6500); return base.extract(p,c); } finally { live.decrementAndGet(); }
                }
            };
            try (var core = new CoreService(db(), slow, muxer())) { UUID id = add(core,"all"); complete(core,id,25); }
            assertEquals(3,peak.get()); assertEquals(4,starts.size()); for(int i=1;i<starts.size();i++) assertTrue(starts.get(i)-starts.get(i-1)>=TimeUnit.MILLISECONDS.toNanos(1950));
        }
    }
    @ParameterizedTest @EnumSource(PlaylistObserver.Boundary.class)
    void forcedKillAtPlaylistBoundariesDoesNotDuplicateAdmittedItems(PlaylistObserver.Boundary boundary) throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0)) {
            Files.writeString(temp.resolve("flat.json"), flat(1,false).toString()); Files.writeString(temp.resolve("full.json"),full(fixture.url(true),1,false).toString()); Path tool = temp.resolve("extractor");
            Files.writeString(tool,"#!/usr/bin/python3\nimport pathlib,sys\np=pathlib.Path(__file__).parent\nname='flat.json' if '--flat-playlist' in sys.argv else 'full.json'\nwith (p/'calls').open('a') as log: log.write(name+'\\n')\nprint((p/name).read_text())\n"); assertTrue(tool.toFile().setExecutable(true));
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"--enable-native-access=ALL-UNNAMED","-Djavax.net.ssl.trustStore="+fixture.keystore,"-Djavax.net.ssl.trustStorePassword=test-only-password",
                    "-cp",System.getProperty("ajdm.test.runtime.classpath"),PlaylistProcessWorker.class.getName(),db().toString(),files().toString(),tool.toString(),boundary.name(),temp.resolve("marker").toString())
                    .redirectOutput(temp.resolve("worker.out").toFile()).redirectError(temp.resolve("worker.err").toFile()).start();
            try { long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12); while(!Files.exists(temp.resolve("marker"))&&child.isAlive()&&System.nanoTime()<deadline)Thread.sleep(20);
                assertTrue(Files.exists(temp.resolve("marker")),Files.readString(temp.resolve("worker.err"))); child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));
            } finally { if(child.isAlive())child.destroyForcibly().waitFor(); }
            long before=Files.readAllLines(temp.resolve("calls")).stream().filter(s->s.equals("full.json")).count();
            try(var core=new CoreService(db(),new YtDlpExtractor(tool),muxer())) {
                UUID id=core.list().stream().filter(r->r.target().equals(files().toString())).findFirst().orElseThrow().id(); assertEquals(DownloadState.PAUSED,group(core,id).state());
                core.resume(id,Map.of(),null);complete(core,id,12); assertEquals(2,core.list().size()); assertArrayEquals(fixture.data,Files.readAllBytes(files().resolve("0001-Item 1.mp4")));
                long after=Files.readAllLines(temp.resolve("calls")).stream().filter(s->s.equals("full.json")).count();
                if(boundary==PlaylistObserver.Boundary.ITEM_ADMITTED||boundary==PlaylistObserver.Boundary.ITEM_COMPLETED)assertEquals(before,after);
            }
        }
    }
    @Test @Timeout(300) void hundredItemPlaylistPassesRealTwoSecondRateGateWithNoFailures() throws Exception {
        AtomicInteger calls=new AtomicInteger();var starts=new CopyOnWriteArrayList<Long>();
        try(var fixture=new MediaHttpsFixture(temp,2049,0)) {
            var base=extractor(fixture,100,false,calls);
            var rated=new MediaExtractor() {
                @Override public PlaylistInfo playlist(URI p,java.util.function.BooleanSupplier c)throws IOException,InterruptedException{return base.playlist(p,c);}
                @Override public MediaInfo extract(URI p,java.util.function.BooleanSupplier c)throws IOException,InterruptedException {
                    long now=System.nanoTime();if(!starts.isEmpty()&&now-starts.getLast()<TimeUnit.MILLISECONDS.toNanos(1950))throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION,"Rate gate rejected");
                    starts.add(now);return base.extract(p,c);
                }
            };
            try(var core=new CoreService(db(),rated,muxer())) {
                UUID id=add(core,"all");complete(core,id,260);assertEquals(100,calls.get());assertEquals(100,starts.size());
                assertTrue(core.playlistItems(id).stream().allMatch(i->i.state()==PlaylistItem.State.COMPLETED));
                for(int i=1;i<=100;i++)assertArrayEquals(fixture.data,Files.readAllBytes(files().resolve(String.format("%04d-Item %d.mp4",i,i))));
            }
        }
    }
}
