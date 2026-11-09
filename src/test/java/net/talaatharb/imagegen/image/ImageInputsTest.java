package net.talaatharb.imagegen.image;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageInputsTest {
    @Test
    void inpaintingPreservesBlackAndUsesWhiteAndSoftMaskValues() {
        BufferedImage original = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        BufferedImage generated = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        BufferedImage mask = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 3; x++) {
            original.setRGB(x, 0, 0xff0000);
            generated.setRGB(x, 0, 0x0000ff);
        }
        mask.setRGB(1, 0, 0xffffff);
        mask.setRGB(2, 0, 0x808080);
        BufferedImage result = Images.blendInpainting(original, generated, mask);
        assertEquals(original.getRGB(0, 0), result.getRGB(0, 0));
        assertEquals(generated.getRGB(1, 0), result.getRGB(1, 0));
        assertEquals(0x7f0080, result.getRGB(2, 0) & 0xffffff);
    }

    @Test
    void acceptsPngJpegAndDataUriButRejectsInvalidDataAndOtherFormats() throws Exception {
        for (String format : new String[] {"png", "jpeg"}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(8, 12, BufferedImage.TYPE_INT_RGB), format, bytes);
            String data = Base64.getEncoder().encodeToString(bytes.toByteArray());
            assertEquals(8, ImageInputs.base64(data).getWidth());
            assertEquals(12, ImageInputs.base64("data:image/" + format + ";base64," + data).getHeight());
        }
        assertThrows(IllegalArgumentException.class, () -> ImageInputs.base64("not-base64"));
        assertThrows(IllegalArgumentException.class, () -> ImageInputs.base64("data:text/plain;base64,eA=="));
        assertThrows(IllegalArgumentException.class, () -> ImageInputs.base64("eA=="));
        var gif = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "gif", gif);
        assertThrows(IllegalArgumentException.class,
                () -> ImageInputs.base64(Base64.getEncoder().encodeToString(gif.toByteArray())));
    }
}
