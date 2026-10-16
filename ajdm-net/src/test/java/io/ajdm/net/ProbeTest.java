package io.ajdm.net;
import io.ajdm.model.*;import java.io.*;import java.net.*;import java.net.http.*;import java.util.*;import javax.net.ssl.*;import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
class ProbeTest {
 private static final URI URI=java.net.URI.create("https://cdn.test/video");
 private record Reply(int statusCode,HttpHeaders headers,InputStream body) implements HttpResponse<InputStream> {
  public HttpRequest request(){return HttpRequest.newBuilder(URI).build();}public Optional<HttpResponse<InputStream>> previousResponse(){return Optional.empty();}public Optional<SSLSession> sslSession(){return Optional.empty();}public URI uri(){return URI;}public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
 }
 private static Reply reply(int status,Map<String,List<String>> headers){return new Reply(status,HttpHeaders.of(headers,(a,b)->true),new ByteArrayInputStream(new byte[0]));}
 private static final Reply RANGE=reply(206,Map.of("Content-Range",List.of("bytes 0-0/100"),"Content-Length",List.of("1"),"ETag",List.of("\"version\"")));
 private static class Transport implements HttpTransport {
  Reply head;IOException failure,getFailure;Reply get=RANGE;final List<String> methods=new ArrayList<>();
  public HttpResponse<InputStream> send(URI u,String method,Map<String,String> h,CancellationScope c)throws IOException {
   methods.add(method);if(method.equals("HEAD") && failure!=null)throw failure;if(method.equals("GET") && getFailure!=null)throw getFailure;
   Reply r=method.equals("HEAD")?head:get;if(method.equals("GET"))assertEquals("bytes=0-0",h.get("Range"));c.register(r.body());return r;
  }public void close(){}
 }
 @Test void oneRangedGetIsTheWholeProbeSoTransfersStartAfterASingleRoundTrip()throws Exception {
  var t=new Transport();t.head=reply(503,Map.of());var probe=new Probe(t).inspect(URI,new CancellationScope());
  assertEquals(List.of("GET"),t.methods);assertEquals(100,probe.contentLength());assertTrue(probe.acceptRanges());assertEquals("\"version\"",probe.etag());
 }
 @Test void anUnsatisfiableByteRangeFallsBackToHeadForEmptyRepresentations()throws Exception {
  var t=new Transport();t.get=reply(416,Map.of("Content-Range",List.of("bytes */0")));t.head=reply(200,Map.of("Content-Length",List.of("0"),"ETag",List.of("\"empty\"")));
  var probe=new Probe(t).inspect(URI,new CancellationScope());
  assertEquals(List.of("GET","HEAD"),t.methods);assertEquals(0,probe.contentLength());assertFalse(probe.acceptRanges());assertEquals("\"empty\"",probe.etag());
 }
 @Test void headQuirksOnTheFallbackPathStillDoNotHideAValidByteRange()throws Exception {
  var t=new Transport();t.get=reply(416,Map.of());t.head=reply(503,Map.of());var target=t;
  // The fallback re-probes with GET after a rejected HEAD; a second 416 is a real protocol answer, not hidden.
  assertThrows(IOException.class,()->new Probe(target).inspect(URI,new CancellationScope()));assertEquals(List.of("GET","HEAD","GET"),t.methods);
  t=new Transport();t.get=reply(416,Map.of());t.failure=new HttpTimeoutException("head timeout");var timed=t;
  assertThrows(IOException.class,()->new Probe(timed).inspect(URI,new CancellationScope()));assertEquals(List.of("GET","HEAD","GET"),t.methods);
 }
 @Test void tlsAndConnectFailuresStayVisible()throws Exception {
  for(IOException error:List.of(new SSLHandshakeException("TLS"),new HttpConnectTimeoutException("connect"),new HttpTimeoutException("response"))){
   var t=new Transport();t.getFailure=error;var target=t;assertSame(error,assertThrows(IOException.class,()->new Probe(target).inspect(URI,new CancellationScope())));assertEquals(List.of("GET"),t.methods);}
 }
 @Test void retryAfterAndRealGetFailuresStillReachTheEngineRetryPolicy() {
  var t=new Transport();t.get=reply(503,Map.of("Retry-After",List.of("5")));var target=t;
  assertEquals(java.time.Duration.ofSeconds(5),assertThrows(RetryableResponseException.class,()->new Probe(target).inspect(URI,new CancellationScope())).retryAfter());assertEquals(List.of("GET"),t.methods);
  t=new Transport();t.get=reply(503,Map.of());var second=t;
  assertThrows(RetryableResponseException.class,()->new Probe(second).inspect(URI,new CancellationScope()));assertEquals(List.of("GET"),t.methods);
 }
 @Test void fallbackNeverAcceptsAnInconsistentRange() {
  var t=new Transport();t.head=reply(503,Map.of());t.get=reply(206,Map.of("Content-Range",List.of("bytes 1-1/100"),"Content-Length",List.of("1")));
  assertEquals(ErrorCode.PROTOCOL_VIOLATION,assertThrows(DownloadException.class,()->new Probe(t).inspect(URI,new CancellationScope())).code());
 }
}
