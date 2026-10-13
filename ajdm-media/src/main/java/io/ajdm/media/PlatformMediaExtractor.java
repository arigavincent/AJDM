package io.ajdm.media;

import io.ajdm.model.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Preserves the extractor inventory and adds public TikTok resolver alternatives. */
public final class PlatformMediaExtractor implements MediaExtractor {
    private final MediaExtractor primary, resolver;
    private volatile boolean authenticated;
    private final java.util.concurrent.atomic.AtomicLong sessionVersion=new java.util.concurrent.atomic.AtomicLong();
    public PlatformMediaExtractor(MediaExtractor primary) { this(primary,new TikTokResolver()); }
    public PlatformMediaExtractor(MediaExtractor primary,MediaExtractor resolver) { this.primary=Objects.requireNonNull(primary);this.resolver=Objects.requireNonNull(resolver); }
    @Override public MediaInfo extract(URI page,BooleanSupplier canceled)throws IOException,InterruptedException { return extract(page,null,canceled); }
    @Override public MediaInfo extractEntry(URI page,String id,BooleanSupplier canceled)throws IOException,InterruptedException { return extract(page,id,canceled); }
    @Override public MediaInfo extractSelected(URI page,String id,String formatId,BooleanSupplier canceled)throws IOException,InterruptedException {
        if(authenticated || !TikTokResolver.supports(page) || !formatId.startsWith("resolver-tiktok-"))return primary(page,id,canceled);
        MediaInfo info=resolver.extract(page,canceled);
        if(id!=null && !id.equals(info.id()))throw new DownloadException(ErrorCode.SOURCE_CHANGED,"Resolver post identity changed");
        try { info.format(formatId); }
        catch(IllegalArgumentException e) { throw new DownloadException(ErrorCode.REFRESH_FAILED,"Chosen resolver tier disappeared"); }
        return info;
    }
    private MediaInfo extract(URI page,String id,BooleanSupplier canceled)throws IOException,InterruptedException {
        long version=sessionVersion.get();
        if(authenticated || !TikTokResolver.supports(page)) return primary(page,id,canceled);
        MediaInfo original=null; DownloadException failure=null;
        try { original=primary(page,id,canceled); }
        catch(DownloadException e) { if(e.code()!=ErrorCode.EXTRACTION_FAILED)throw e;failure=e; }
        if(authenticated || sessionVersion.get()!=version) {
            if(original!=null)return original;
            throw failure;
        }
        MediaInfo alternatives;
        try { alternatives=resolver.extract(page,canceled); }
        catch(DownloadException e) {
            if(e.code()==ErrorCode.CANCELED || e.code()==ErrorCode.DRM_PROTECTED)throw e;
            if(original!=null)return original;
            throw failure==null?e:failure;
        }
        if(id!=null && !id.equals(alternatives.id())) {
            if(original!=null)return original;
            throw new DownloadException(ErrorCode.SOURCE_CHANGED,"Resolver post identity changed");
        }
        if(original==null)return alternatives;
        if(!original.id().equals(alternatives.id()))return original;
        var formats=new ArrayList<>(original.formats());var ids=new HashSet<String>();formats.forEach(f->ids.add(f.formatId()));
        alternatives.formats().stream().filter(f->ids.add(f.formatId())).forEach(formats::add);
        return new MediaInfo(original.id(),original.title(),original.uploader(),original.duration(),original.pageUri(),formats,original.excludedFormats());
    }
    private MediaInfo primary(URI page,String id,BooleanSupplier canceled)throws IOException,InterruptedException { return id==null?primary.extract(page,canceled):primary.extractEntry(page,id,canceled); }
    @Override public PlaylistInfo playlist(URI page,BooleanSupplier canceled)throws IOException,InterruptedException { return primary.playlist(page,canceled); }
    @Override public synchronized void configureCookies(Path path)throws IOException {
        boolean previous=authenticated;authenticated=true;sessionVersion.incrementAndGet();
        try { primary.configureCookies(path);authenticated=path!=null; }
        catch(IOException | RuntimeException e) { authenticated=previous;throw e; }
    }
    @Override public synchronized void configureBrowser(MediaBrowser browser)throws IOException {
        boolean previous=authenticated;authenticated=true;sessionVersion.incrementAndGet();
        try { primary.configureBrowser(browser);authenticated=browser!=null; }
        catch(IOException | RuntimeException e) { authenticated=previous;throw e; }
    }
    @Override public void close() { try { primary.close(); } finally { resolver.close(); } }
}
