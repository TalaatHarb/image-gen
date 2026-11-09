package net.talaatharb.imagegen.generation.sdcpp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.talaatharb.imagegen.service.ImageEditingRequest.Mode;

class ImageTaskArgumentsTest {
    @Test
    void instructionUsesReferenceInsteadOfNoisingTheInput() {
        Path input = Path.of("input.png");
        assertEquals(List.of("--ref-image", input.toAbsolutePath().toString()),
                SdCppImageGenerationService.editArguments(Mode.INSTRUCTION, input, null, 0.75f));
    }

    @Test
    void maskedEditingUsesInitMaskAndStrength() {
        Path input = Path.of("input.png");
        Path mask = Path.of("mask.png");
        assertEquals(List.of("--init-img", input.toAbsolutePath().toString(), "--strength", "0.75",
                "--mask", mask.toAbsolutePath().toString()),
                SdCppImageGenerationService.editArguments(Mode.INPAINT, input, mask, 0.75f));
        assertFalse(SdCppImageGenerationService.editArguments(Mode.IMG2IMG, input, null, 0.5f).contains("--mask"));
    }
}
