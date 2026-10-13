package io.ajdm.media;
import io.ajdm.model.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import java.io.*;import java.net.*;import java.nio.file.*;import java.util.*;import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
class PlatformMediaExtractorTest {
 @Test void explicitResolverSelectionAndRenewalSkipPrimaryAndVerifyIdentity() throws Exception {
  var primary=new Stub(null);primary.failure=ErrorCode.EXTRACTION_FAILED;var resolver=new Stub(info("1234567890","resolver-tiktok-standard"));
  try(var facade=new PlatformMediaExtractor(primary,resolver)) {
   assertEquals("1234567890",facade.extractSelected(page,null,"resolver-tiktok-standard",()->false).id());
   assertEquals("1234567890",facade.extractSelected(page,"1234567890","resolver-tiktok-standard",()->false).id());assertEquals(0,primary.calls);
   resolver.value=info("999","resolver-tiktok-standard");assertEquals(ErrorCode.SOURCE_CHANGED,assertThrows(DownloadException.class,()->facade.extractSelected(page,"1234567890","resolver-tiktok-standard",()->false)).code());
   resolver.value=info("1234567890","resolver-tiktok-watermarked");assertEquals(ErrorCode.REFRESH_FAILED,assertThrows(DownloadException.class,()->facade.extractSelected(page,"1234567890","resolver-tiktok-standard",()->false)).code());
  }
 }
 @TempDir Path temp; private final URI page=URI.create("https://www.tiktok.com/@creator/video/1234567890");
 private MediaInfo info(String id,String format){return new MediaInfo(id,"Title","",null,page,List.of(new MediaFormat(format,"mp4","unknown","unknown",null,null,null,null,null,null,null,false,false,"","",new DownloadSource(URI.create("https://cdn.tiktokcdn.com/file"),Map.of()))),0);}
 private class Stub implements MediaExtractor {MediaInfo value;ErrorCode failure;int calls,entryCalls;boolean closed;Stub(MediaInfo v){value=v;}public MediaInfo extract(URI p,BooleanSupplier c)throws IOException{calls++;if(failure!=null)throw new DownloadException(failure,"secret");return value;}public MediaInfo extractEntry(URI p,String id,BooleanSupplier c)throws IOException{entryCalls++;return extract(p,c);}public void configureCookies(Path p){}public void configureBrowser(MediaBrowser b){}public void close(){closed=true;}}
 @Test void selectedBrowserSessionsNeverReachPublicResolversAndClearRestoresAnonymousRoute() throws Exception {
  var primary=new Stub(info("1234567890","original"));var resolver=new Stub(info("1234567890","resolver-tiktok-standard"));
  try(var facade=new PlatformMediaExtractor(primary,resolver)){
   facade.configureBrowser(MediaBrowser.FIREFOX);facade.extract(page,()->false);assertEquals(0,resolver.calls);
   facade.extractSelected(page,null,"resolver-tiktok-standard",()->false);assertEquals(0,resolver.calls);
   facade.configureCookies(null);facade.extract(page,()->false);assertEquals(1,resolver.calls);
  }
 }
 @Test void addsStableResolverOptionsToWorkingExtractionAndFallsBackWhenItFails() throws Exception {
  var primary=new Stub(info("1234567890","original"));var resolver=new Stub(info("1234567890","resolver-tiktok-standard"));
  try(var facade=new PlatformMediaExtractor(primary,resolver)){
   assertEquals(List.of("original","resolver-tiktok-standard"),facade.extract(page,()->false).formats().stream().map(MediaFormat::formatId).toList());
   primary.failure=ErrorCode.EXTRACTION_FAILED;assertEquals("resolver-tiktok-standard",facade.extract(page,()->false).formats().getFirst().formatId());
   assertEquals("1234567890",facade.extractEntry(page,"1234567890",()->false).id());assertEquals(1,primary.entryCalls);
  }assertTrue(primary.closed);assertTrue(resolver.closed);
 }
 @Test void neverMasksDrmCancellationOrMergesAResolverForADifferentPost() throws Exception {
  var primary=new Stub(info("1234567890","original"));var resolver=new Stub(info("999","resolver-tiktok-standard"));
  try(var facade=new PlatformMediaExtractor(primary,resolver)){
   assertEquals(1,facade.extract(page,()->false).formats().size());
   for(var code:List.of(ErrorCode.DRM_PROTECTED,ErrorCode.CANCELED,ErrorCode.INVALID_STATE)){primary.failure=code;int count=resolver.calls;assertEquals(code,assertThrows(DownloadException.class,()->facade.extract(page,()->false)).code());assertEquals(count,resolver.calls);}
  }
 }
 @Test void cookieConfiguredLinksAndInstagramNeverGoToThirdPartyResolvers() throws Exception {
  var primary=new Stub(info("1234567890","original"));var resolver=new Stub(info("1234567890","resolver-tiktok-standard"));
  try(var facade=new PlatformMediaExtractor(primary,resolver)){
   facade.extract(URI.create("https://www.instagram.com/reel/abc/"),()->false);assertEquals(0,resolver.calls);
   facade.configureCookies(temp.resolve("cookies.txt"));facade.extract(page,()->false);assertEquals(0,resolver.calls);
   facade.configureCookies(null);facade.extract(page,()->false);assertEquals(1,resolver.calls);
  }
 }
 @Test void failedResolverPreservesPrimaryFormatsAndBothFailuresRemainSafe() throws Exception {
  var primary=new Stub(info("1234567890","original"));var resolver=new Stub(null);resolver.failure=ErrorCode.EXTRACTION_FAILED;
  try(var facade=new PlatformMediaExtractor(primary,resolver)){
   assertEquals("original",facade.extract(page,()->false).formats().getFirst().formatId());primary.failure=ErrorCode.EXTRACTION_FAILED;
   assertEquals(ErrorCode.EXTRACTION_FAILED,assertThrows(DownloadException.class,()->facade.extract(page,()->false)).code());
  }
 }
 @Test void refreshedResolverCannotSubstituteAnotherPostForTheSavedSelection() throws Exception {
  var primary=new Stub(null);primary.failure=ErrorCode.EXTRACTION_FAILED;var resolver=new Stub(info("999","resolver-tiktok-standard"));
  try(var facade=new PlatformMediaExtractor(primary,resolver)){
   assertEquals(ErrorCode.SOURCE_CHANGED,assertThrows(DownloadException.class,()->facade.extractEntry(page,"1234567890",()->false)).code());
  }
 }
 @Test void changingCookieConfigurationDuringExtractionPreventsResolverUse() throws Exception {
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  var resolver=new Stub(info("1234567890","resolver-tiktok-standard"));
  var primary=new Stub(info("1234567890","original")){
   @Override public MediaInfo extract(URI p,BooleanSupplier c)throws IOException{entered.countDown();try{if(!release.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new IOException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException();}return super.extract(p,c);}
  };
  try(var facade=new PlatformMediaExtractor(primary,resolver);var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
   var result=pool.submit(()->facade.extract(page,()->false));assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
   facade.configureCookies(temp.resolve("cookies.txt"));facade.configureCookies(null);release.countDown();
   assertEquals("original",result.get(2,java.util.concurrent.TimeUnit.SECONDS).formats().getFirst().formatId());assertEquals(0,resolver.calls);
  }finally{release.countDown();}
 }
}
