package net.talaatharb.imagegen.generation.sdcpp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the console output of {@code sd-cli} into progress updates. The program prints log lines prefixed with
 * {@code [I]}/{@code [W]}/{@code [E]} and redraws progress bars with carriage returns: {@code |####| 12/196} while
 * loading weights and {@code |====| 3/4 - 2.1s/it} while sampling.
 */
public final class SdCppOutputParser {
    /** Share of the overall progress spent on each phase. */
    static final double LOAD_END = 0.15;
    static final double SAMPLE_END = 0.9;
    static final double DECODE_END = 0.98;

    private static final Pattern BAR = Pattern.compile("\\|([#=> ]*)\\|\\s*(\\d+)/(\\d+)(.*)");

    /** A progress update derived from one line of output. */
    public record Update(String message, double fraction) {
    }

    private enum Phase {
        LOADING, SAMPLING, DECODING
    }

    private Phase phase = Phase.LOADING;
    private final List<String> errors = new ArrayList<>();
    private final List<String> tail = new ArrayList<>();

    /** Parses one line (already split on {@code \r} and {@code \n}); returns null when it carries no progress. */
    public Update accept(String rawLine) {
        String line = rawLine.strip();
        if (line.isEmpty()) {
            return null;
        }
        remember(line);
        if (line.startsWith("[E]")) {
            errors.add(clean(line));
            return null;
        }
        if (line.contains("generating image:") || line.contains("sampling using")) {
            if (line.contains("generating image:")) {
                phase = Phase.SAMPLING;
                return new Update("Sampling", LOAD_END);
            }
            return null;
        }
        if (line.contains("decoding") && line.contains("latent")) {
            phase = Phase.DECODING;
            return new Update("Decoding image", SAMPLE_END);
        }
        if (line.contains("generate_image completed")) {
            return new Update("Saving image", 0.99);
        }
        Matcher bar = BAR.matcher(line);
        if (!bar.find()) {
            return null;
        }
        int done = Integer.parseInt(bar.group(2));
        int total = Integer.parseInt(bar.group(3));
        if (total <= 0) {
            return null;
        }
        double part = Math.min(1, done / (double) total);
        boolean step = bar.group(1).indexOf('=') >= 0 || bar.group(1).indexOf('>') >= 0;
        if (step && phase != Phase.DECODING) {
            phase = Phase.SAMPLING;
            return new Update("Step " + done + " of " + total, LOAD_END + (SAMPLE_END - LOAD_END) * part);
        }
        return switch (phase) {
            case LOADING -> new Update("Loading model weights", LOAD_END * part);
            // Weights of the diffusion model are loaded lazily once sampling starts.
            case SAMPLING -> new Update("Loading diffusion model weights", LOAD_END);
            case DECODING -> new Update("Decoding image", SAMPLE_END + (DECODE_END - SAMPLE_END) * part);
        };
    }

    /** Error lines ({@code [E]}) printed so far, without the source location suffix. */
    public List<String> errors() {
        return List.copyOf(errors);
    }

    /** Last lines of output, for error reports when no {@code [E]} line was printed. */
    public List<String> tail() {
        return List.copyOf(tail);
    }

    /** A short human readable explanation of a failed run. */
    public String failureSummary() {
        if (!errors.isEmpty()) {
            return String.join("\n", errors.subList(Math.max(0, errors.size() - 3), errors.size()));
        }
        return tail.isEmpty() ? "no output" : String.join("\n", tail.subList(Math.max(0, tail.size() - 3),
                tail.size()));
    }

    private void remember(String line) {
        if (BAR.matcher(line).find()) {
            return;
        }
        tail.add(clean(line));
        if (tail.size() > 20) {
            tail.remove(0);
        }
    }

    /** Removes the {@code [E] } prefix and the {@code --- file.cpp:123} suffix. */
    static String clean(String line) {
        String text = line;
        if (text.length() > 3 && text.charAt(0) == '[' && text.charAt(2) == ']') {
            text = text.substring(3).strip();
        }
        int source = text.lastIndexOf(" --- ");
        if (source > 0) {
            text = text.substring(0, source).strip();
        }
        return text;
    }
}
