package net.talaatharb.imagegen.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpTaskSettingsTest {
    @TempDir Path dir;

    @Test
    void persistsEditingAndUpscalingDefaultsWithoutChangingServerStartupState() {
        Path file = dir.resolve("settings.properties");
        AppSettings settings = new AppSettings(file);
        settings.setMcpTaskDefaults("sd15-lcm", "flux2-klein-4b", "inpaint", 0.5f,
                "realesrgan-anime-6b", 64);
        AppSettings reloaded = new AppSettings(file);
        assertEquals("sd15-lcm", reloaded.mcpEditingModel());
        assertEquals("flux2-klein-4b", reloaded.mcpInstructionModel());
        assertEquals("inpaint", reloaded.mcpEditingMode());
        assertEquals(0.5f, reloaded.mcpEditingStrength());
        assertEquals("realesrgan-anime-6b", reloaded.mcpUpscalingModel());
        assertEquals(64, reloaded.mcpUpscalingTileSize());
        assertThrows(IllegalArgumentException.class,
                () -> settings.setMcpTaskDefaults("sd15", "flux2-klein-4b", "inpaint", Float.NaN,
                        "realesrgan-x4plus", 64));
        assertThrows(IllegalArgumentException.class,
                () -> settings.setMcpTaskDefaults("sd15", "flux2-klein-4b", "inpaint", 0.75f,
                        "realesrgan-x4plus", 65));
        assertEquals(64, new AppSettings(file).mcpUpscalingTileSize());
    }
}
