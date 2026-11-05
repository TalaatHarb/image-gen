package net.talaatharb.imagegen.generation.sdcpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SdCppPlatformTest {

    @Test
    void picksWindowsBuilds() {
        SdCppPlatform cpu = SdCppPlatform.detect("Windows 11", "amd64", false).orElseThrow();
        assertEquals("sd-master-58bb3c1-bin-win-cpu-x64.zip", cpu.asset());
        assertEquals("sd-cli.exe", cpu.executable());
        assertFalse(cpu.gpu());
        SdCppPlatform gpu = SdCppPlatform.detect("Windows 11", "amd64", true).orElseThrow();
        assertEquals("sd-master-58bb3c1-bin-win-vulkan-x64.zip", gpu.asset());
        assertEquals("vulkan", gpu.variant());
        assertTrue(gpu.url().startsWith("https://github.com/leejet/stable-diffusion.cpp/releases/download/"
                + SdCppPlatform.RELEASE + "/"));
    }

    @Test
    void picksLinuxAndMacBuilds() {
        assertEquals("sd-master-58bb3c1-bin-Linux-Ubuntu-26.04-x86_64.zip",
                SdCppPlatform.detect("Linux", "amd64", false).orElseThrow().asset());
        assertEquals("sd-master-58bb3c1-bin-Linux-Ubuntu-26.04-x86_64-vulkan.zip",
                SdCppPlatform.detect("Linux", "x86_64", true).orElseThrow().asset());
        SdCppPlatform mac = SdCppPlatform.detect("Mac OS X", "aarch64", false).orElseThrow();
        assertEquals("metal", mac.variant());
        assertEquals("sd-cli", mac.executable());
    }

    @Test
    void unsupportedPlatformsNeedACustomBuild() {
        assertTrue(SdCppPlatform.detect("Linux", "aarch64", false).isEmpty());
        assertTrue(SdCppPlatform.detect("Mac OS X", "x86_64", false).isEmpty());
        assertTrue(SdCppPlatform.detect("Windows 11", "x86", false).isEmpty());
    }
}
