package io.ajdm.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CategoriesTest {
    @Test void filesSortIntoIdmStyleCategoriesAndUnknownTypesStayInTheMainFolder() {
        assertEquals(Categories.VIDEO, Categories.of("Movie.2024.MKV"));
        assertEquals(Categories.MUSIC, Categories.of("song.m4a"));
        assertEquals(Categories.COMPRESSED, Categories.of("debian-12.iso"));
        assertEquals(Categories.COMPRESSED, Categories.of("src.tar.gz"));
        assertEquals(Categories.PROGRAMS, Categories.of("Setup.exe"));
        assertEquals(Categories.DOCUMENTS, Categories.of("report.pdf"));
        assertEquals(Categories.OTHER, Categories.of("README"));
        assertEquals(Categories.OTHER, Categories.of(null));
        Path base = Path.of("/home/u/Downloads");
        assertEquals(base.resolve("Video"), Categories.folder(base, "a.mp4", true));
        assertEquals(base, Categories.folder(base, "a.mp4", false));
        assertEquals(base, Categories.folder(base, "notes.xyz", true));
    }
}
