package comp3011;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SubtitleFormatter {
	
	// ChatGPT assisted with these values
    private static final long MIN_US = 1_000_000;
    private static final long MAX_US = 7_000_000;
    private static final long PAUSE_US = 600_000;

    private SubtitleFormatter() {
    }

    public static List<SubtitleInstance> format(List<SubtitleInstance> wordinstances) {
        List<SubtitleInstance> sorted = new ArrayList<>(wordinstances);
        sorted.sort(Comparator.comparingLong(SubtitleInstance::startUs));

        List<SubtitleInstance> result = new ArrayList<>();

        String text = "";
        long startUs = 0;
        long endUs = 0;

        for (SubtitleInstance instance : sorted) {
            String word = instance.text().trim();

            if (word.isEmpty() || instance.endUs() <= instance.startUs()) {
                continue;
            }

            // extremely long single words cannot fit on one subtitle line
            if (word.length() > 42) {
                word = word.substring(0, 39) + "...";
            }

            String candidate = text.isEmpty() ? word : text + " " + word;

            boolean needsNewinstance = !text.isEmpty() && (
                    wrap(candidate) == null
                    || instance.endUs() - startUs > MAX_US
                    || instance.startUs() - endUs > PAUSE_US
                    || (endsSentence(text) && endUs - startUs >= MIN_US)
            );

            if (needsNewinstance) {
                add(result, startUs, endUs, text);
                text = "";
            }

            if (text.isEmpty()) {
                startUs = instance.startUs();
                text = word;
            } else {
                text += " " + word;
            }
            endUs = instance.endUs();
        }
        add(result, startUs, endUs, text);
        return List.copyOf(result);
    }

    private static boolean endsSentence(String text) {
        return text.endsWith(".") || text.endsWith("!") || text.endsWith("?");
    }

    private static void add(List<SubtitleInstance> result, long start, long end, String text) {
        if (text.isBlank()) {
            return;
        }
        String wrapped = wrap(text);
        if (wrapped != null) {
            long displayEnd = Math.min(start + MAX_US, Math.max(end, start + MIN_US));
            result.add(new SubtitleInstance(start, displayEnd, wrapped));
        }
    }

    // returns null if the text requires more than 2 42 character lines
    private static String wrap(String text) {
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();

        boolean secondLine = false;

        for (String word : text.trim().split("\\s+")) {
            if (!secondLine && append(first, word)) {
                continue;
            }
            secondLine = true;
            if (!append(second, word)) {
                return null;
            }
        }
        return second.isEmpty() ? first.toString() : first + "\n" + second;
    }

    private static boolean append(StringBuilder line, String word) {
        int length = line.length() + word.length() + (line.isEmpty() ? 0 : 1);

        if (length > 42) {
            return false;
        }

        if (!line.isEmpty()) {
            line.append(' ');
        }

        line.append(word);
        return true;
    }
}