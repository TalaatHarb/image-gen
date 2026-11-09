package net.talaatharb.imagegen.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;

/** Editable full-resolution mask; display scaling never changes the mask dimensions. */
final class MaskPainter {
    private final BufferedImage mask;

    MaskPainter(int width, int height, BufferedImage existing) {
        if (existing != null && (existing.getWidth() != width || existing.getHeight() != height)) {
            throw new IllegalArgumentException("The mask must match the input image dimensions");
        }
        mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        if (existing != null) {
            var graphics = mask.createGraphics();
            try {
                graphics.drawImage(existing, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
    }

    void stroke(double fromX, double fromY, double toX, double toY, float diameter, boolean erase) {
        var graphics = mask.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(erase ? Color.BLACK : Color.WHITE);
            graphics.setStroke(new BasicStroke(diameter, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.draw(new Line2D.Double(fromX, fromY, toX, toY));
        } finally {
            graphics.dispose();
        }
    }

    void clear() {
        var graphics = mask.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, mask.getWidth(), mask.getHeight());
        } finally {
            graphics.dispose();
        }
    }

    BufferedImage image() {
        return mask;
    }

    BufferedImage overlay() {
        BufferedImage overlay = new BufferedImage(mask.getWidth(), mask.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                int rgb = mask.getRGB(x, y);
                int luminance = (int) Math.round(0.299 * ((rgb >> 16) & 255)
                        + 0.587 * ((rgb >> 8) & 255) + 0.114 * (rgb & 255));
                overlay.setRGB(x, y, ((luminance / 2) << 24) | 0xff3030);
            }
        }
        return overlay;
    }
}
