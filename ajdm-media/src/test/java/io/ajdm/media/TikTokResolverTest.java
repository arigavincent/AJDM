package io.ajdm.media;

import com.sun.net.httpserver.HttpServer;
import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TikTokResolverTest {
    @Test void productionDeadlineAcceptsAValidSixSecondResolverResponse() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",e->{try{Thread.sleep(6000);var bytes="{\"data\":{\"id\":\"1234567890\",\"play\":\"https://cdn.tiktokcdn.com/v.mp4\"}}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}catch(Exception ignored){}finally{e.close();}});server.start();
        try(var resolver=new TikTokResolver(List.of(URI.create("http://localhost:"+server.getAddress().getPort()+"/?")))) {
            assertEquals("1234567890",resolver.extract(PAGE,()->false).id());
        }finally{server.stop(0);}
    }
    private static final URI PAGE = URI.create("https://www.tiktok.com/@creator/video/1234567890");
    private static MediaInfo parse(String json) throws Exception { return TikTokResolver.parse(json.getBytes(StandardCharsets.UTF_8), PAGE); }
    @Test void realResponseShapeKeepsStableChoicesWithoutInventingResolutionOrCodecs() throws Exception {
        var info = parse("""
          {"code":0,"data":{"id":"1234567890","title":"Sample","duration":30,"size":2000000,
          "hdplay":"https://v16m.tiktokcdn-us.com/hd.mp4?sig=a","hd_size":3000000,
          "play":"https://v16m.tiktokcdn-us.com/sd.mp4?sig=b","wmplay":"https://v16m.tiktokcdn-us.com/wm.mp4",
          "music":"https://v16-ies-music.tiktokcdn-us.com/music.mp3"}}
          """);
        assertEquals("1234567890", info.id());
        assertEquals(List.of("resolver-tiktok-hd", "resolver-tiktok-standard", "resolver-tiktok-watermarked", "resolver-tiktok-audio"), info.formats().stream().map(MediaFormat::formatId).toList());
        var hd = info.format("resolver-tiktok-hd"); assertNull(hd.height()); assertEquals("unknown",hd.vcodec()); assertEquals(3_000_000,hd.sizeBytes());
        assertEquals(MediaFormat.Kind.UNKNOWN, hd.kind()); assertEquals(MediaFormat.Kind.AUDIO_ONLY,info.format("resolver-tiktok-audio").kind());
        assertEquals("https://www.tiktok.com/",hd.source().headers().get("Referer")); assertFalse(hd.source().headers().containsKey("Cookie"));
        assertEquals("mp4",MediaSelection.custom(info, hd.formatId(),null).container());
    }
    @Test void nestedAddressListsUseNumericIdentityAndReportedDimensionsOnly() throws Exception {
        var info=parse("""
          {"aweme_detail":{"aweme_id":"1234567890","desc":"Nested","duration_ms":42000,
          "video":{"width":720,"height":1280,"play_addr":{"url_list":["http://unsafe/","https://cdn.tiktokcdn.com/s.mp4"]}}}}
          """);
        assertEquals(42d,info.duration()); assertEquals(1280,info.formats().getFirst().height()); assertEquals(-1,info.formats().getFirst().sizeBytes());
    }
    @Test void duplicateChoicesDoNotDuplicateUrlsAndRefreshKeepsTheSameSelector() throws Exception {
        var old=parse("{\"data\":{\"id\":\"1234567890\",\"play\":\"https://cdn.tiktokcdn.com/v.mp4?old\",\"wmplay\":\"https://cdn.tiktokcdn.com/v.mp4?old\"}}");
        var next=parse("{\"data\":{\"id\":\"1234567890\",\"play\":\"https://cdn.tiktokcdn.com/v.mp4?new\"}}");
        assertEquals(1,old.formats().size()); assertEquals(old.formats().getFirst().formatId(),next.formats().getFirst().formatId());
    }
    @Test void protectedMediaAndWatermarkedAddressFallbackKeepTheirMeaning() throws Exception {
        assertEquals(ErrorCode.DRM_PROTECTED,assertThrows(DownloadException.class,()->parse("{\"has_drm\":true}")).code());
        var info=parse("{\"data\":{\"id\":\"1234567890\",\"video\":{\"download_addr\":{\"url_list\":[\"https://cdn.tiktokcdn.com/w.mp4\"]}}}}");
        assertEquals("resolver-tiktok-watermarked",info.formats().getFirst().formatId());assertTrue(info.formats().getFirst().note().contains("With watermark"));
    }
    @Test void rejectsUntrustedUrlsMissingIdentityWrongPostAndFailedServiceResponse() {
        for(String json:List.of(
            "{\"data\":{\"play\":\"https://cdn.tiktokcdn.com/v.mp4\"}}",
            "{\"data\":{\"id\":\"999\",\"play\":\"https://cdn.tiktokcdn.com/v.mp4\"}}",
            "{\"code\":-1,\"data\":{\"id\":\"1234567890\",\"play\":\"https://cdn.tiktokcdn.com/v.mp4\"}}")) assertThrows(DownloadException.class,()->parse(json));
        for(String url:List.of("http://cdn.tiktokcdn.com/v.mp4","https://127.0.0.1/v.mp4","https://tiktokcdn.com.evil.test/v.mp4","https://cdn.tiktokcdn.com@evil.test/v.mp4","file:///tmp/v.mp4","https://cdn.tiktokcdn.com/v.mp4#x"))
            assertThrows(DownloadException.class,()->parse("{\"data\":{\"id\":\"1234567890\",\"play\":\""+url+"\"}}"));
    }
    @Test void supportsOnlyPostLinksAndRemovesTrackingBeforeSendingToResolver() {
        assertEquals("https://vt.tiktok.com/AbC123/",TikTokResolver.publicPage(URI.create("https://vt.tiktok.com/AbC123/?token=secret")).toString());
        assertEquals(PAGE,TikTokResolver.publicPage(URI.create(PAGE+"?tracking=secret")));
        for(String url:List.of("https://www.tiktok.com/","https://www.tiktok.com/@creator","https://www.tiktok.com/login","https://tiktok.com.evil.test/@x/video/123","http://vt.tiktok.com/a/","https://vt.tiktok.com:8443/a/")) assertFalse(TikTokResolver.supports(URI.create(url)));
    }
    @Test void boundedJavaMetadataRequestsFallThrough429AndCarryNoSessionHeaders() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        var queries=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/first",exchange->{queries.add(exchange.getRequestURI().getRawQuery());exchange.sendResponseHeaders(429,-1);exchange.close();});
        server.createContext("/second",exchange->{
            assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            var b="{\"data\":{\"id\":\"1234567890\",\"play\":\"https://cdn.tiktokcdn.com/v.mp4\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});server.start();
        try(var resolver=new TikTokResolver(List.of(URI.create("http://localhost:"+server.getAddress().getPort()+"/first?"),URI.create("http://localhost:"+server.getAddress().getPort()+"/second?")),Duration.ofSeconds(2),2048)){
            assertEquals("1234567890",resolver.extract(URI.create(PAGE+"?token=secret"),()->false).id());assertEquals(1,queries.size());assertFalse(queries.getFirst().contains("secret"));
            assertEquals(ErrorCode.CANCELED,assertThrows(DownloadException.class,()->resolver.extract(PAGE,()->true)).code());
        }finally{server.stop(0);}
    }
    @Test void oversizedMetadataRedirectAndSlowBodyFailWithoutLeakingUrls() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);var pool=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(pool);
        server.createContext("/large",e->{byte[] b=new byte[2048];e.sendResponseHeaders(200,b.length);try{e.getResponseBody().write(b);}finally{e.close();}});
        server.createContext("/redirect",e->{e.getResponseHeaders().add("Location","https://evil.test/secret");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/slow",e->{e.sendResponseHeaders(200,0);try{Thread.sleep(1000);e.getResponseBody().write(1);}catch(Exception ignored){}finally{e.close();}});server.start();
        try{for(String path:List.of("large","redirect","slow"))try(var resolver=new TikTokResolver(List.of(URI.create("http://localhost:"+server.getAddress().getPort()+"/"+path+"?")),Duration.ofMillis(250),1024)){
            long start=System.nanoTime();var error=assertThrows(DownloadException.class,()->resolver.extract(PAGE,()->false));assertEquals(ErrorCode.EXTRACTION_FAILED,error.code());assertFalse(error.userMessage().contains("secret"));assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2));
        }}finally{server.stop(0);pool.shutdownNow();}
    }
}
