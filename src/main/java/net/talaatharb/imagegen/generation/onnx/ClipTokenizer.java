package net.talaatharb.imagegen.generation.onnx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The byte-level BPE tokenizer of OpenAI's CLIP text encoder, as used by Stable Diffusion
 * ({@code tokenizer/vocab.json} + {@code tokenizer/merges.txt}).
 */
public final class ClipTokenizer {
    public static final int MAX_LENGTH = 77;
    static final String BOS = "<|startoftext|>";
    static final String EOS = "<|endoftext|>";
    private static final Pattern WORDS = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final String[] BYTE_TO_UNICODE = byteToUnicode();

    private final Map<String, Integer> vocab;
    private final Map<String, Integer> ranks;
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();
    private final int bos;
    private final int eos;
    private final int pad;

    ClipTokenizer(Map<String, Integer> vocab, List<String> merges, String padToken) {
        this.vocab = Map.copyOf(vocab);
        this.ranks = new HashMap<>();
        for (int i = 0; i < merges.size(); i++) {
            ranks.putIfAbsent(merges.get(i), i);
        }
        this.bos = id(BOS);
        this.eos = id(EOS);
        this.pad = padToken == null || !vocab.containsKey(padToken) ? eos : vocab.get(padToken);
    }

    private int id(String token) {
        Integer value = vocab.get(token);
        if (value == null) {
            throw new IllegalArgumentException("The vocabulary has no " + token + " token");
        }
        return value;
    }

    /**
     * Loads {@code vocab.json} and {@code merges.txt} from a diffusers {@code tokenizer} directory; the padding
     * token comes from {@code special_tokens_map.json} when present (Stable Diffusion 2 pads with {@code !}).
     */
    public static ClipTokenizer load(Path dir) throws IOException {
        ObjectMapper json = new ObjectMapper();
        Map<String, Integer> vocab = new HashMap<>();
        try (InputStream in = Files.newInputStream(dir.resolve("vocab.json"))) {
            json.readTree(in).fields().forEachRemaining(e -> vocab.put(e.getKey(), e.getValue().asInt()));
        }
        List<String> merges = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("merges.txt"), StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("#version")) {
                merges.add(line.strip());
            }
        }
        String padToken = null;
        Path special = dir.resolve("special_tokens_map.json");
        if (Files.isRegularFile(special)) {
            JsonNode node = json.readTree(special.toFile()).get("pad_token");
            if (node != null) {
                padToken = node.isTextual() ? node.asText() : node.path("content").asText(null);
            }
        }
        return new ClipTokenizer(vocab, merges, padToken);
    }

    /** Token ids of the prompt: start token, at most 75 text tokens, end token, padding up to 77. */
    public int[] encode(String text) {
        int[] ids = new int[MAX_LENGTH];
        java.util.Arrays.fill(ids, pad);
        ids[0] = bos;
        int position = 1;
        for (int token : tokenize(text)) {
            if (position >= MAX_LENGTH - 1) {
                break;
            }
            ids[position++] = token;
        }
        ids[position] = eos;
        return ids;
    }

    /** Token ids of the text without start/end tokens or padding. */
    public List<Integer> tokenize(String text) {
        String clean = text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        List<Integer> ids = new ArrayList<>();
        Matcher matcher = WORDS.matcher(clean);
        while (matcher.find()) {
            StringBuilder mapped = new StringBuilder();
            for (byte b : matcher.group().getBytes(StandardCharsets.UTF_8)) {
                mapped.append(BYTE_TO_UNICODE[b & 0xff]);
            }
            for (String piece : bpe(mapped.toString())) {
                Integer id = vocab.get(piece);
                ids.add(id == null ? eos : id);
            }
        }
        return ids;
    }

    private List<String> bpe(String token) {
        return cache.computeIfAbsent(token, this::merge);
    }

    private List<String> merge(String token) {
        List<String> word = new ArrayList<>();
        int[] codePoints = token.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            String symbol = new String(codePoints, i, 1);
            word.add(i == codePoints.length - 1 ? symbol + "</w>" : symbol);
        }
        while (word.size() > 1) {
            int best = -1;
            int bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < word.size() - 1; i++) {
                Integer rank = ranks.get(word.get(i) + " " + word.get(i + 1));
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            String first = word.get(best);
            String second = word.get(best + 1);
            List<String> merged = new ArrayList<>(word.size());
            for (int i = 0; i < word.size(); i++) {
                if (i < word.size() - 1 && word.get(i).equals(first) && word.get(i + 1).equals(second)) {
                    merged.add(first + second);
                    i++;
                } else {
                    merged.add(word.get(i));
                }
            }
            word = merged;
        }
        return List.copyOf(word);
    }

    /** GPT-2's reversible mapping of bytes to printable unicode characters. */
    private static String[] byteToUnicode() {
        String[] table = new String[256];
        boolean[] printable = new boolean[256];
        for (int b = '!'; b <= '~'; b++) {
            printable[b] = true;
        }
        for (int b = 0xA1; b <= 0xAC; b++) {
            printable[b] = true;
        }
        for (int b = 0xAE; b <= 0xFF; b++) {
            printable[b] = true;
        }
        int next = 0;
        for (int b = 0; b < 256; b++) {
            table[b] = printable[b] ? String.valueOf((char) b) : String.valueOf((char) (256 + next++));
        }
        return table;
    }
}
