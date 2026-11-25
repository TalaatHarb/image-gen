package net.talaatharb.imagegen.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpuSettingsTest {
    @TempDir Path dir;

    @Test
    void persistsIndependentDeviceChoicesAndRejectsInvalidValues() {
        Path file = dir.resolve("settings.properties");
        AppSettings settings = new AppSettings(file);
        assertEquals("", settings.sdCppDevice());
        assertEquals("", settings.directMlDevice());
        settings.setGpuDevices("vulkan1", "NVIDIA RTX", "0");
        AppSettings reloaded = new AppSettings(file);
        assertEquals("vulkan1", reloaded.sdCppDevice());
        assertEquals("NVIDIA RTX", reloaded.sdCppDeviceDescription());
        assertEquals("0", reloaded.directMlDevice());
        assertThrows(IllegalArgumentException.class, () -> settings.setGpuDevices("vulkan1", "", "0"));
        assertThrows(IllegalArgumentException.class, () -> settings.setGpuDevices("", "", "-1"));
        settings.setGpuDevices("", "", "");
        assertEquals("", new AppSettings(file).sdCppDevice());
    }
}
