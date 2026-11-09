package net.talaatharb.imagegen.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** Bounds compressed bytes and decoded pixels before allocating image buffers. */
public final class ImageInputs {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final long MAX_PIXELS = 16_777_216;
    private ImageInputs() {}

    public static BufferedImage read(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) {
            throw new IllegalArgumentException("Image exceeds the 8 MiB file limit");
        }
        try (var input = Files.newInputStream(path)) {
            return decode(input.readNBytes(MAX_BYTES + 1));
        }
    }

    public static BufferedImage base64(String data) throws IOException {
        if (data.startsWith("data:")) {
            int comma = data.indexOf(',');
            if (comma < 0 || !data.substring(0, comma).matches("data:image/(png|jpeg);base64")) {
                throw new IllegalArgumentException("Use a PNG/JPEG base64 data URI");
            }
            data = data.substring(comma + 1);
        }
        if (data.length() > (MAX_BYTES + 2L) / 3 * 4) {
            throw new IllegalArgumentException("Image exceeds the 8 MiB file limit");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Image must contain valid base64 PNG/JPEG data", e);
        }
        return decode(bytes);
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Image exceeds the 8 MiB file limit");
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Image data is not a readable PNG/JPEG");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) {
                    throw new IllegalArgumentException("Only PNG and JPEG images are accepted");
                }
                reader.setInput(input);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new IllegalArgumentException("Input image exceeds the 16 megapixel limit");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
