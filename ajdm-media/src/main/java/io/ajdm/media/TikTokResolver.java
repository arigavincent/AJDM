package io.ajdm.media;

import com.google.gson.*;
import io.ajdm.model.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Public-post metadata only. AJDM's engine receives the returned HTTPS sources. */
public final class TikTokResolver implements MediaExtractor {
    private static final Set<String> CDN_DOMAINS = Set.of("tiktokcdn.com", "tiktokcdn-us.com", "tiktok.com",
            "tiktokv.com", "tikwm.com", "muscdn.com", "byteoversea.com", "ibytedtos.com");
    private final HttpClient client;
    private final List<URI> endpoints;
    private final Duration timeout;
    private final int limit;
    private final Semaphore slots = new Semaphore(3);
    private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public TikTokResolver() {
        this(List.of(URI.create("https://www.tikwm.com/api/?"), URI.create("https://api.douyin.wtf/api/tiktok/info?")));
    }
    TikTokResolver(List<URI> endpoints) { this(endpoints,Duration.ofSeconds(10),2*1024*1024); }
    // Endpoint injection is restricted to tests. Production endpoints are fixed HTTPS services.
    TikTokResolver(List<URI> endpoints, Duration timeout, int limit) {
        this.endpoints = List.copyOf(endpoints); this.timeout = timeout; this.limit = limit;
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public static boolean supports(URI page) {
        try { publicPage(page); return true; } catch (IllegalArgumentException e) { return false; }
    }
    static URI publicPage(URI page) {
        new DownloadSource(page, Map.of());
        String host = page.getHost().toLowerCase(Locale.ROOT), path = page.getRawPath();
        if (!"https".equalsIgnoreCase(page.getScheme()) || page.getPort() != -1 && page.getPort() != 443 || path == null)
            throw new IllegalArgumentException("Not a public TikTok post link");
        boolean shortLink = Set.of("vt.tiktok.com", "vm.tiktok.com").contains(host) && path.matches("/[A-Za-z0-9]+/?");
        boolean post = Set.of("www.tiktok.com", "m.tiktok.com", "tiktok.com").contains(host)
                && (path.matches("/@[A-Za-z0-9._-]+/video/[0-9]+/?") || path.matches("/v/[0-9]+\\.html"));
        if (!shortLink && !post) throw new IllegalArgumentException("Not a public TikTok post link");
        return URI.create("https://" + host + path); // No tracking/session query parameters leave AJDM.
    }
    @Override public MediaInfo extract(URI page, BooleanSupplier canceled) throws IOException, InterruptedException {
        URI publicLink;
        try { publicLink = publicPage(page); }
        catch (IllegalArgumentException e) { throw failed(); }
        check(canceled);
        if (!slots.tryAcquire()) throw new DownloadException(ErrorCode.INVALID_STATE, "Resolver admission limit");
        try {
            for (URI endpoint : endpoints) {
                check(canceled);
                var request = HttpRequest.newBuilder(URI.create(endpoint + "url=" + URLEncoder.encode(publicLink.toString(), StandardCharsets.UTF_8) + "&hd=1"))
                        .timeout(timeout).header("User-Agent", "AJDM/0.1 metadata").header("Accept", "application/json").GET().build();
                var future = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), limit));
                active.add(future); long deadline = System.nanoTime() + timeout.toNanos();
                try {
                    while (true) {
                        check(canceled);
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) throw new TimeoutException();
                        try {
                            var response = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                            if (response.statusCode() == 200) return parse(response.body(), page);
                            break;
                        } catch (TimeoutException e) { if (System.nanoTime() >= deadline) throw e; }
                    }
                } catch (DownloadException e) { if (e.code() != ErrorCode.EXTRACTION_FAILED) throw e; }
                catch (ExecutionException | TimeoutException | CancellationException e) { check(canceled); }
                finally { future.cancel(true); active.remove(future); }
            }
            throw failed();
        } finally { slots.release(); }
    }
    private void check(BooleanSupplier canceled) throws DownloadException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (closed || canceled.getAsBoolean()) throw new DownloadException(ErrorCode.CANCELED, "Resolver canceled");
    }
    private static DownloadException failed() { return new DownloadException(ErrorCode.EXTRACTION_FAILED, "Public resolver unavailable"); }

    static MediaInfo parse(byte[] bytes, URI page) throws DownloadException {
        try {
            JsonObject root = MediaParser.json(bytes);
            if(MediaParser.bool(root,"has_drm"))throw new DownloadException(ErrorCode.DRM_PROTECTED,"Protected resolver media");
            Double code = MediaParser.number(root, "code"); if (code != null && code != 0) throw failed();
            JsonObject data = object(root, "data"), item = object(data, "aweme_detail");
            if (item == null) item = object(data, "data");
            if (item == null) item = data == null ? object(root, "aweme_detail") : data;
            if (item == null) throw failed();
            if(MediaParser.bool(item,"has_drm"))throw new DownloadException(ErrorCode.DRM_PROTECTED,"Protected resolver media");
            String id = text(item, "id", text(item, "aweme_id", ""));
            if (!id.matches("[0-9]{1,32}")) throw failed();
            String path = publicPage(page).getPath();
            var match = java.util.regex.Pattern.compile("/(?:video/|v/)([0-9]+)").matcher(path);
            if (match.find() && !id.equals(match.group(1))) throw failed();
            var video = object(item, "video"); var formats = new ArrayList<MediaFormat>(); var urls = new HashSet<URI>();
            add(formats, urls, "hd", first(item.get("hdplay"), field(video,"play_addr_h264")),
                    "HD · No watermark · TikTok resolver", size(item,"hd_size"), video, false);
            add(formats, urls, "standard", first(item.get("play"), field(video,"play_addr")),
                    "Standard · No watermark · TikTok resolver", size(item,"size"), video, false);
            add(formats, urls, "watermarked", first(item.get("wmplay"), field(video,"play_addr_watermark"),field(video,"download_addr")),
                    "Original · With watermark · TikTok resolver", size(item,"wm_size"), video, false);
            var music = object(item,"music_info");
            add(formats, urls, "audio", first(item.get("music"),field(music,"play_url"),field(music,"playUrl")),
                    "Original audio · TikTok resolver", size(item,"music_size"), null, true);
            if (formats.isEmpty()) throw failed();
            Double duration = MediaParser.number(item,"duration_ms");
            if (duration != null) duration /= 1000; else duration = MediaParser.number(item,"duration");
            var author = object(item,"author");
            return new MediaInfo(id, text(item,"title",text(item,"desc",id)), text(author,"nickname",""), duration, page, formats, 0);
        } catch (DownloadException e) { throw e; }
        catch (IOException | IllegalArgumentException | IllegalStateException | ArithmeticException e) { throw failed(); }
    }
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement e = field(parent,key); return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
    private static JsonElement field(JsonObject parent,String key) { return parent == null ? null : parent.get(key); }
    private static String text(JsonObject parent,String key,String fallback) { return parent == null ? fallback : MediaParser.string(parent,key,fallback); }
    private static Long size(JsonObject parent,String key) {
        JsonElement e = field(parent,key); if(e == null || e.isJsonNull()) return null;
        if(!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        long n=e.getAsBigDecimal().longValueExact(); if(n<0)throw new IllegalArgumentException(); return n == 0 ? null : n;
    }
    private static URI first(JsonElement... candidates) {
        for(var e:candidates) {
            if(e == null || e.isJsonNull()) continue;
            if(e.isJsonObject()) { URI u=first(e.getAsJsonObject().get("url_list"));if(u!=null)return u; }
            else if(e.isJsonArray()) { for(var value:e.getAsJsonArray()){URI u=first(value);if(u!=null)return u;} }
            else if(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                try {
                    URI u=URI.create(e.getAsString().strip());new DownloadSource(u,Map.of());
                    String host=u.getHost().toLowerCase(Locale.ROOT);
                    if("https".equalsIgnoreCase(u.getScheme()) && (u.getPort()==-1 || u.getPort()==443)
                            && CDN_DOMAINS.stream().anyMatch(d->host.equals(d) || host.endsWith("."+d))) return u;
                }catch(IllegalArgumentException ignored){ /* Ignore unsafe candidates, never fetch them. */ }
            }
        }
        return null;
    }
    private static void add(List<MediaFormat> formats,Set<URI> urls,String tier,URI uri,String note,Long size,JsonObject video,boolean audio) {
        if(uri==null || !urls.add(uri))return;
        Integer width=video==null?null:integer(video,"width"),height=video==null?null:integer(video,"height");
        formats.add(new MediaFormat("resolver-tiktok-"+tier,audio?"mp3":"mp4",audio?"none":"unknown",audio?"mp3":"unknown",
                width,height,null,null,null,size,null,false,false,note,"",new DownloadSource(uri,Map.of("Referer","https://www.tiktok.com/","User-Agent","Mozilla/5.0"))));
    }
    private static Integer integer(JsonObject parent,String key) { Long value=size(parent,key);return value==null?null:Math.toIntExact(value); }
    @Override public void close() { closed=true;active.forEach(f->f.cancel(true));client.shutdownNow(); }
}
