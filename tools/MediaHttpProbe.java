import io.ajdm.media.*;
import io.ajdm.model.*;
import io.ajdm.net.*;
import java.nio.file.Path;
import java.util.*;

/** Diagnostic requests use AJDM's transport and close bodies without downloading a file. */
public final class MediaHttpProbe {
    public static void main(String[] args) {
        if (args[0].equals("--state")) {
            try (var store = new io.ajdm.store.SqliteDownloadStore(Path.of(args[1]))) {
                for (var saved : store.list()) {
                    var scope = new CancellationScope();
                    try (var transport = new JdkHttpTransport(saved.request())) {
                        for (String method : List.of("HEAD", "GET")) {
                            var response = transport.send(saved.request().uri(), method, method.equals("GET") ? Map.of("Range", "bytes=0-0") : Map.of(), scope);
                            try (var body = response.body()) {
                                System.out.println(store.mediaOrigin(saved.request().id()).orElseThrow().formatId() + " " + method
                                        + " status=" + response.statusCode() + " range=" + response.headers().firstValue("Content-Range").isPresent());
                            } finally { scope.unregister(response.body()); }
                        }
                    }
                }
            } catch (Exception failure) { System.out.println("Saved-source diagnostic failed; source details suppressed."); }
            return;
        }
        try (var extractor = new YtDlpExtractor(Path.of("/home/ariga/.local/bin/yt-dlp"))) {
            MediaInfo info = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try { info = extractor.extract(java.net.URI.create(args[0]), () -> false); break; }
                catch (DownloadException failed) { if (attempt == 2 || failed.code() != ErrorCode.EXTRACTION_FAILED) throw failed; }
            }
            for (String id : List.of("bytevc1_540p_678331-0", "bytevc1_540p_678331-1", "audio")) {
                var format = info.format(id); var scope = new CancellationScope();
                var request = new TransferRequest(UUID.randomUUID(), format.source().uri(), Path.of("/tmp/ajdm-probe-unused"), format.source().headers(), TransferOptions.defaults());
                try (var transport = new JdkHttpTransport(request)) {
                    for (String method : List.of("HEAD", "GET")) {
                        var response = transport.send(format.source().uri(), method, method.equals("GET") ? Map.of("Range", "bytes=0-0") : Map.of(), scope);
                        try (var body = response.body()) {
                            System.out.println(id + " " + method + " status=" + response.statusCode()
                                    + " redirect=" + !response.uri().getHost().equalsIgnoreCase(format.source().uri().getHost())
                                    + " range=" + response.headers().firstValue("Content-Range").isPresent()
                                    + " headerNames=" + format.source().headers().keySet());
                        } finally { scope.unregister(response.body()); }
                    }
                }
            }
        } catch (DownloadException failure) { System.out.println(failure.code().publicCode()); }
        catch (Exception failure) { System.out.println("Diagnostic could not complete; source details suppressed."); }
    }
}
