package io.ajdm.ui;

import javafx.scene.Scene;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

final class Snapshot {
    private Snapshot() { }
    static void write(Scene scene, Path path) throws IOException {
        var image = scene.snapshot(null); var pixels = image.getPixelReader();
        var output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, pixels.getArgb(x, y));
        Path normalized = path.toAbsolutePath().normalize(); Files.createDirectories(normalized.getParent());
        ImageIO.write(output, "png", normalized.toFile());
    }
}
