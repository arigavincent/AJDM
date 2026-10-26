package io.ajdm.engine;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HttpWarmupTest {
    @TempDir Path temp;
    @Test void constructionWarmsTheHttpStackUnlessConnectionWarmupIsOff() throws Exception {
        try (var engine = new AjdmEngine(temp.resolve("on.db"))) {
            assertTrue(engine.warmsUp()); assertTrue(engine.awaitWarmup(Duration.ofSeconds(20)));
        }
        try (var engine = new AjdmEngine(temp.resolve("limits.db"), EngineLimits.defaults().withoutPrewarm())) {
            assertFalse(engine.warmsUp()); assertTrue(engine.awaitWarmup(Duration.ZERO));
        }
        System.setProperty("ajdm.prewarm", "false");
        try (var engine = new AjdmEngine(temp.resolve("property.db"))) { assertFalse(engine.warmsUp()); }
        finally { System.clearProperty("ajdm.prewarm"); }
    }
}
