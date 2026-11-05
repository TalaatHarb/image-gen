package net.talaatharb.imagegen.generation.sdcpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SdCppOutputParserTest {
    /** Output of a real SDXS run (abridged). */
    private static final String LOG = String.join("\n",
            "[I] loading model from 'sdxs.safetensors' --- diffusion_engine.cpp:685",
            "[I] Version: SDXS (512-DS)  --- diffusion_engine.cpp:975",
            "  |##                                                |   4/196 -   0.00MB/s\r"
                    + "  |##################################################| 196/196 -   1.09GB/s",
            "[I] loading tensors completed, taking 0.21s --- model_loader.cpp:1450",
            "[I] generating image: 1/1 - seed 7 --- image.cpp:967",
            "  |##                                                |   7/280 -  23.80MB/s",
            "  |=========================                         | 1/2 -   3.16s/it\r"
                    + "  |==================================================| 2/2 -   3.16s/it",
            "[I] sampling completed, taking 3.18s --- image.cpp:1000",
            "[I] decoding 1 latents --- image.cpp:652",
            "  |##################################################| 67/67 -  11.10MB/s",
            "[I] generate_image completed in 6.13s --- image.cpp:1193");

    private static List<SdCppOutputParser.Update> parse(SdCppOutputParser parser, String text) {
        List<SdCppOutputParser.Update> updates = new ArrayList<>();
        for (String line : text.split("[\r\n]")) {
            SdCppOutputParser.Update update = parser.accept(line);
            if (update != null) {
                updates.add(update);
            }
        }
        return updates;
    }

    @Test
    void mapsPhasesToIncreasingProgress() {
        List<SdCppOutputParser.Update> updates = parse(new SdCppOutputParser(), LOG);
        double last = -1;
        for (SdCppOutputParser.Update update : updates) {
            assertTrue(update.fraction() >= last, "progress went backwards at " + update);
            last = update.fraction();
        }
        assertTrue(updates.stream().anyMatch(u -> u.message().equals("Step 1 of 2")));
        assertTrue(updates.stream().anyMatch(u -> u.message().equals("Step 2 of 2")
                && u.fraction() == SdCppOutputParser.SAMPLE_END));
        assertTrue(updates.stream().anyMatch(u -> u.message().equals("Decoding image")));
        assertEquals("Saving image", updates.get(updates.size() - 1).message());
    }

    @Test
    void collectsErrors() {
        SdCppOutputParser parser = new SdCppOutputParser();
        assertNull(parser.accept("[E] get sd version from file failed: 'x.gguf' --- model_loader.cpp:120"));
        parser.accept("[E] new_sd_ctx_t failed --- main.cpp:500");
        assertEquals(List.of("get sd version from file failed: 'x.gguf'", "new_sd_ctx_t failed"), parser.errors());
        assertTrue(parser.failureSummary().contains("new_sd_ctx_t failed"));
    }

    @Test
    void summarizesTailWithoutErrors() {
        SdCppOutputParser parser = new SdCppOutputParser();
        parser.accept("something odd happened");
        assertEquals("something odd happened", parser.failureSummary());
        assertEquals("no output", new SdCppOutputParser().failureSummary());
    }
}
