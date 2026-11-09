package net.talaatharb.imagegen.generation.sdcpp;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import net.talaatharb.imagegen.model.*;
import net.talaatharb.imagegen.service.*;

@EnabledIfSystemProperty(named = "imagegen.integration", matches = "true")
class ImageTasksIntegrationTest {
    private final ModelStore store = new ModelStore(ModelStore.defaultRoot());
    private final HuggingFaceDownloader downloader = new HuggingFaceDownloader();
    private final SdCppEngine engine = new SdCppEngine(store, downloader, () -> false);

    static BufferedImage source(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new Color(30, 130, 40));
        g.fillRect(0, 0, size, size);
        g.setColor(Color.ORANGE);
        g.fillOval(size / 4, size / 4, size / 2, size / 2);
        g.dispose();
        return image;
    }

    private static void save(String name, BufferedImage image) throws Exception {
        Path dir = Path.of("target", "integration-images");
        Files.createDirectories(dir);
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
    }

    @Test
    void realImg2imgAndInpainting() throws Exception {
        var generation = new SdCppImageGenerationService(SdCppModels.SD15_MODEL, engine, store, downloader);
        var service = new SdCppImageEditingService(generation);
        service.install(ProgressListener.NONE);
        BufferedImage source = source(256);
        BufferedImage mask = new BufferedImage(256, 256, BufferedImage.TYPE_BYTE_GRAY);
        var g = mask.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(128, 0, 128, 256);
        g.dispose();
        save("editing-input", source);
        save("editing-mask", mask);
        for (var mode : List.of(ImageEditingRequest.Mode.IMG2IMG, ImageEditingRequest.Mode.INPAINT)) {
            var request = new ImageEditingRequest(source, mask, mode,
                    new ImageGenerationRequest("a photo of an orange on green grass", "", 256, 256, 4, 7, 42), 0.75f);
            var result = service.edit(request, ProgressListener.NONE, PreviewListener.NONE);
            assertEquals(256, result.image().getWidth());
            assertEquals(42, result.request().seed());
            assertNotEquals(source.getRGB(180, 100), result.image().getRGB(180, 100));
            if (mode == ImageEditingRequest.Mode.INPAINT) {
                assertEquals(source.getRGB(40, 40), result.image().getRGB(40, 40), "Black mask must preserve pixels");
            }
            save("editing-" + mode.id(), result.image());
            System.out.println(mode + " generated in " + result.elapsed().toMillis() + " ms");
        }
    }

    @Test
    void realEsrganStandaloneUpscaling() throws Exception {
        var service = new SdCppUpscalingService("realesrgan-x4plus", "Real-ESRGAN x4plus",
                "RealESRGAN_x4plus.pth", 67_040_989, engine, store, downloader);
        service.install(ProgressListener.NONE);
        var result = service.upscale(source(64), 64, ProgressListener.NONE);
        assertEquals(256, result.image().getWidth());
        assertEquals(256, result.image().getHeight());
        assertEquals(4, result.scale());
        save("upscaled-esrgan", result.image());
        System.out.println("Real-ESRGAN generated in " + result.elapsed().toMillis() + " ms");
    }
}
