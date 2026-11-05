package net.talaatharb.imagegen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImageGenerationRequestTest {

    @Test
    void validatesInput() {
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationRequest(" ", "", 512, 512, 4, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationRequest("x", "", 500, 512, 4, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationRequest("x", "", 512, 512, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationRequest("x", "", 512, 512, 4, -1, 1));
    }

    @Test
    void randomSeedIsReplacedByAConcreteOne() {
        ImageGenerationRequest request = new ImageGenerationRequest("x", null, 512, 768, 4, 1, -1);
        assertTrue(request.randomSeed());
        assertEquals("", request.negativePrompt());
        ImageGenerationRequest seeded = request.withSeed(42);
        assertFalse(seeded.randomSeed());
        assertEquals(42, seeded.seed());
        assertEquals(768, seeded.height());
    }
}
