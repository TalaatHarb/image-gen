package net.talaatharb.imagegen.generation.sdcpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.talaatharb.imagegen.service.ImageServiceException;

class SdCppDeviceTest {
    @Test
    void selectsBackendByReportedNameAndRejectsChangedDevices() {
        List<SdCppEngine.Device> devices = SdCppEngine.parseDevices(
                "initializing\nCPU\tCPU\nvulkan0\tIntel Graphics\nvulkan1\tNVIDIA RTX\n");
        assertEquals(2, devices.size());
        var rtx = new SdCppEngine.Device("vulkan1", "NVIDIA RTX");
        assertEquals(List.of("--backend", "vulkan1"), SdCppEngine.deviceArguments(rtx, devices));
        assertThrows(ImageServiceException.class, () -> SdCppEngine.deviceArguments(rtx,
                SdCppEngine.parseDevices("vulkan0\tNVIDIA RTX\nvulkan1\tIntel Graphics\n")));
    }
}
