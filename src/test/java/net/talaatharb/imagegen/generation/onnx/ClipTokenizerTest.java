package net.talaatharb.imagegen.generation.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ClipTokenizerTest {

    private static ClipTokenizer tokenizer() {
        Map<String, Integer> vocab = new HashMap<>();
        String[] tokens = {"!", "a</w>", "c", "a", "t</w>", "ca", "cat</w>", ",</w>", "d", "o", "g</w>", "do",
                "dog</w>", "<|startoftext|>", "<|endoftext|>"};
        for (int i = 0; i < tokens.length; i++) {
            vocab.put(tokens[i], i);
        }
        return new ClipTokenizer(vocab, List.of("c a", "ca t</w>", "d o", "do g</w>"), null);
    }

    @Test
    void appliesMergesByRank() {
        ClipTokenizer tokenizer = tokenizer();
        assertEquals(List.of(6, 1, 12), tokenizer.tokenize("Cat  A   DOG"));
        assertEquals(List.of(6, 7, 12), tokenizer.tokenize("cat, dog"));
    }

    @Test
    void encodesWithStartEndAndPadding() {
        int[] ids = tokenizer().encode("cat");
        assertEquals(ClipTokenizer.MAX_LENGTH, ids.length);
        assertEquals(13, ids[0]);
        assertEquals(6, ids[1]);
        assertEquals(14, ids[2]);
        assertEquals(14, ids[76]);
    }

    @Test
    void truncatesLongPrompts() {
        int[] ids = tokenizer().encode("cat ".repeat(200));
        assertEquals(6, ids[75]);
        assertEquals(14, ids[76]);
    }

    @Test
    void usesTheConfiguredPadToken() {
        Map<String, Integer> vocab = Map.of("<|startoftext|>", 1, "<|endoftext|>", 2, "!", 0);
        int[] ids = new ClipTokenizer(vocab, List.of(), "!").encode("");
        assertEquals(1, ids[0]);
        assertEquals(2, ids[1]);
        assertEquals(0, ids[2]);
    }
}
