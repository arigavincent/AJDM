package io.ajdm.cli;

import io.ajdm.core.*;
import io.ajdm.media.*;
import io.ajdm.model.*;
import java.nio.file.*;
import java.util.*;

public final class PlaylistProcessWorker {
    private PlaylistProcessWorker() {}
    public static void main(String[] args)throws Exception {
        try(var core=new CoreService(Path.of(args[0]),new YtDlpExtractor(Path.of(args[2])),(v,ve,a,ae,out,c,cancel)->Files.write(out,Files.readAllBytes(v),StandardOpenOption.CREATE_NEW),MediaJobObserver.none(),(job,item,boundary)->{
            if(!boundary.name().equals(args[3]))return;Files.writeString(Path.of(args[4]),job.id().toString());
            try{Thread.sleep(60_000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("Boundary interrupted");}
        })){
            core.addPlaylist("https://page.test/list","all",new PlaylistSelection.Choice("P1080",null,null),Map.of(),Path.of(args[1]),ConflictPolicy.RENAME,1,true);Thread.sleep(60_000);
        }
    }
}
