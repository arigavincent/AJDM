import com.google.gson.*;import io.ajdm.core.*;import io.ajdm.ipc.*;import io.ajdm.media.*;import io.ajdm.model.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
/** Resume only an isolated qualification job, never the user's database. */
class PlatformResumeProbe {
 static boolean idle(IpcClient live)throws Exception {for(var e:live.call("list",new JsonObject()).getAsJsonArray("downloads"))if(!Set.of("COMPLETED","CANCELED","PAUSED","FAILED").contains(e.getAsJsonObject().get("state").getAsString()))return false;return true;}
 public static void main(String[] args)throws Exception {
  Path directory=Path.of(args[0]).toAbsolutePath().normalize();if(!directory.startsWith(Path.of("artifacts/ar-download-port").toAbsolutePath().normalize()))throw new IllegalArgumentException("Qualification state only");
  var live=new IpcClient(Path.of(args[1]));var report=new JsonObject();
  try(var core=new CoreService(directory.resolve("state/db"),new PlatformMediaExtractor(new YtDlpExtractor(Path.of("/home/ariga/.local/bin/yt-dlp"))),new FfmpegMuxer(Path.of("/usr/bin/ffmpeg")))){
   if(!idle(live))throw new IllegalStateException("User transfer active");var before=core.list().getFirst();if(before.state()!=DownloadState.PAUSED)throw new IllegalArgumentException("Expected paused sample");
   report.addProperty("retainedBytes",before.durableBytes());long start=System.nanoTime(),end=start+TimeUnit.SECONDS.toNanos(180),next=start;
   core.resume(before.id(),Map.of("Referer","https://www.tiktok.com/"),ConflictPolicy.RENAME,4);
   CoreService.Row row;
   while(true){row=core.list().getFirst();if(Set.of(DownloadState.COMPLETED,DownloadState.FAILED,DownloadState.PAUSED).contains(row.state()))break;
    if(!idle(live)||System.nanoTime()>end){core.pause(row.id());row=core.list().getFirst();break;}
    if(System.nanoTime()>=next){System.out.println(row.state()+" bytes="+row.writtenBytes()+" total="+row.totalBytes());next=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);}Thread.sleep(1000);
   }
   report.addProperty("state",row.state().name());report.addProperty("bytes",row.writtenBytes());report.addProperty("seconds",(System.nanoTime()-start)/1e9);if(row.error()!=null)report.addProperty("error",row.error().publicCode());
   if(row.state()==DownloadState.COMPLETED){var hash=java.security.MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(Path.of(row.target()))){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)hash.update(b,0,n);}report.addProperty("sha256",HexFormat.of().formatHex(hash.digest()));report.addProperty("file",Path.of(row.target()).getFileName().toString());}
  }catch(DownloadException e){report.addProperty("error",e.code().publicCode());}catch(Exception e){report.addProperty("error","Qualification incomplete");}
  Files.writeString(directory.resolve("resume-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println(report);
 }
}
