package net.talaatharb.imagegen.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class MaskPainterTest {
    @Test
    void paintsContinuousStrokesErasesAndClearsAtOriginalResolution() {
        MaskPainter painter = new MaskPainter(256, 128, null);
        painter.stroke(20, 40, 200, 40, 12, false);
        assertEquals(0xffffff, painter.image().getRGB(100, 40) & 0xffffff);
        assertEquals(0, painter.image().getRGB(100, 70) & 0xffffff);
        painter.stroke(100, 40, 100, 40, 20, true);
        assertEquals(0, painter.image().getRGB(100, 40) & 0xffffff);
        assertEquals(0xffffff, painter.image().getRGB(150, 40) & 0xffffff);
        assertTrue((painter.overlay().getRGB(150, 40) >>> 24) > 0);
        assertEquals(0, painter.overlay().getRGB(100, 70) >>> 24);
        painter.clear();
        assertEquals(0, painter.image().getRGB(150, 40) & 0xffffff);
        assertEquals(256, painter.image().getWidth());
        assertEquals(128, painter.image().getHeight());
    }

    @Test
    void editsACopySoCancelledChangesDoNotAlterExistingMask() {
        BufferedImage existing = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        existing.setRGB(20, 20, 0x808080);
        MaskPainter painter = new MaskPainter(64, 64, existing);
        assertEquals(existing.getRGB(20, 20), painter.image().getRGB(20, 20));
        painter.stroke(20, 20, 20, 20, 10, false);
        assertEquals(0x808080, existing.getRGB(20, 20) & 0xffffff);
        assertEquals(0xffffff, painter.image().getRGB(20, 20) & 0xffffff);
        assertThrows(IllegalArgumentException.class, () -> new MaskPainter(128, 64, existing));
    }
}
