package io.ajdm.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClipboardWatcherTest {
    @Test void filesAndVideoPagesAreOfferedOnceAndEverythingElseIsIgnored() {
        var watcher = new ClipboardWatcher();
        var iso = watcher.offer("  https://cdimage.debian.org/debian-12.7.0-amd64-netinst.iso \n");
        assertEquals(ClipboardWatcher.Kind.FILE, iso.kind());
        assertEquals("https://cdimage.debian.org/debian-12.7.0-amd64-netinst.iso", iso.url());
        assertNull(watcher.offer("https://cdimage.debian.org/debian-12.7.0-amd64-netinst.iso"), "The same link is offered once");
        assertEquals(ClipboardWatcher.Kind.MEDIA, watcher.offer("https://www.youtube.com/watch?v=abc").kind());
        assertEquals(ClipboardWatcher.Kind.MEDIA, watcher.offer("https://youtu.be/abc?si=x").kind());
        assertEquals(ClipboardWatcher.Kind.FILE, watcher.offer("http://files.example/Setup.EXE?token=1").kind());
        for (String ignored : new String[]{"https://news.example/article", "hello world", "ftp://host/file.zip", "file:///etc/passwd.zip",
                "https://user:pw@host/file.zip", "https://evil-youtube.com.example/video", "https://host/a.zip b.zip", "", "https://notyoutube.com/x"}) {
            assertNull(ClipboardWatcher.classify(ignored), ignored);
        }
        assertNull(watcher.offer(null));
    }
}
