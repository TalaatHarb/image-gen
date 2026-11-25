package net.talaatharb.imagegen.generation.sdcpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.imagegen.model.HuggingFaceDownloader;
import net.talaatharb.imagegen.model.ModelStore;

class WindowsVulkanDriversTest {
    @TempDir Path root;

    @Test
    void onlyAddsManifestsForTheRegisteredLibrary() throws Exception {
        Path driver = Files.createFile(root.resolve("driver64.dll"));
        Files.createFile(root.resolve("driver32.dll"));
        Files.createFile(root.resolve("safety.dll"));
        Path valid = root.resolve("vulkan.json");
        Files.writeString(valid, "{\"ICD\":{\"library_path\":\"./driver64.dll\"}}");
        Files.writeString(root.resolve("32bit.json"), "{\"ICD\":{\"library_path\":\"./driver32.dll\"}}");
        Files.writeString(root.resolve("sc.json"), "{\"ICD\":{\"library_path\":\"./safety.dll\"}}");
        Files.writeString(root.resolve("layers.json"), "{\"layers\":[{\"library_path\":\"./driver64.dll\"}]}");
        assertEquals(List.of(valid), WindowsVulkanDrivers.manifestsForLibrary(driver));
        assertTrue(WindowsVulkanDrivers.manifestsForLibrary(root.resolve("missing.dll")).isEmpty());
    }

    @Test
    void preservesExplicitVulkanEnvironment() {
        for (String key : List.of("VK_DRIVER_FILES", "VK_ICD_FILENAMES", "VK_ADD_DRIVER_FILES")) {
            Map<String, String> environment = new HashMap<>(Map.of(key, "user-configured.json"));
            WindowsVulkanDrivers.addManifests(environment, List.of(root.resolve("vulkan.json")));
            assertEquals(Map.of(key, "user-configured.json"), environment);
        }
        Map<String, String> environment = new HashMap<>();
        WindowsVulkanDrivers.addManifests(environment, List.of(root.resolve("vulkan.json")));
        assertEquals(root.resolve("vulkan.json").toString(), environment.get("VK_ADD_DRIVER_FILES"));
        assertFalse(environment.containsKey("VK_DRIVER_FILES"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @EnabledIfSystemProperty(named = "imagegen.integration", matches = "true")
    void listsInstalledGpuDevicesWithoutShellOverrides() {
        var engine = new SdCppEngine(new ModelStore(ModelStore.defaultRoot()), new HuggingFaceDownloader(), () -> true);
        List<SdCppEngine.Device> devices = engine.listDevices();
        assertFalse(devices.isEmpty());
        String expected = System.getProperty("imagegen.expectedGpu", "");
        assertTrue(devices.stream().anyMatch(device -> device.description().contains(expected)), devices.toString());
        System.out.println("Detected GPUs: " + devices);
    }
}
