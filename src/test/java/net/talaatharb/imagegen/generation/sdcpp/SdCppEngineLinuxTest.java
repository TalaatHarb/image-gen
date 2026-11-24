package net.talaatharb.imagegen.generation.sdcpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import net.talaatharb.imagegen.model.HuggingFaceDownloader;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.model.ProgressListener;
import net.talaatharb.imagegen.service.ImageServiceException;
import net.talaatharb.imagegen.service.PreviewListener;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class SdCppEngineLinuxTest {
    @TempDir Path root;

    @Test
    void selectsLocalBuildForTheRequestedDevice() throws Exception {
        AtomicBoolean gpu = new AtomicBoolean();
        SdCppEngine engine = new SdCppEngine(new ModelStore(root), new HuggingFaceDownloader(), gpu::get);
        SdCppPlatform cpu = SdCppPlatform.detect("Linux", "x86_64", false).orElseThrow();
        SdCppPlatform vulkan = SdCppPlatform.detect("Linux", "x86_64", true).orElseThrow();
        Path downloaded = engine.directory(cpu).resolve("sd-cli");
        Files.createDirectories(downloaded.getParent());
        Files.createFile(downloaded);
        assertEquals(downloaded, engine.executable());

        Path localCpu = engine.localExecutable(cpu);
        Files.createDirectories(localCpu.getParent());
        Files.createFile(localCpu);
        assertEquals(localCpu, engine.executable());

        gpu.set(true);
        Path localGpu = engine.localExecutable(vulkan);
        Files.createDirectories(localGpu.getParent());
        Files.createFile(localGpu);
        assertEquals(localGpu, engine.executable());
        assertTrue(engine.isInstalled());
    }

    @Test
    void reportsHowToFixIncompatibleLinuxLibraries() throws Exception {
        SdCppEngine engine = new SdCppEngine(new ModelStore(root), new HuggingFaceDownloader(), () -> false);
        Path executable = engine.executable();
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "#!/bin/sh\necho 'libm.so.6: version `GLIBC_2.43'\\'' not found' >&2\nexit 1\n");
        assertTrue(executable.toFile().setExecutable(true));

        ImageServiceException error = assertThrows(ImageServiceException.class, () -> engine.run(List.of(),
                root.resolve("out.png"), null, ProgressListener.NONE, PreviewListener.NONE));
        assertTrue(error.getMessage().contains("GLIBC_2.43"));
        assertTrue(error.getMessage().contains("build-linux-sdcpp.sh"));
    }
}
