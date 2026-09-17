package mage.player.seat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Text clean-up for prompts and rules: strip the engine's HTML and the
 * " [58c]" object-id suffixes it embeds in log names. Ported from mage-bench's
 * BridgePromptFormatting (MIT, Gregor Stocks).
 */
public final class Fmt {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HEX_SUFFIX = Pattern.compile(" \\[[0-9a-f]{3}\\]");

    private Fmt() {
    }

    public static String stripHtml(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = s.replaceAll("(?i)<br\\s*/?>", ": ");
        out = HTML_TAG.matcher(out).replaceAll("");
        return HEX_SUFFIX.matcher(out).replaceAll("");
    }

    public static List<String> stripHtmlList(List<String> list) {
        if (list == null) {
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) {
            out.add(stripHtml(s));
        }
        return out;
    }

    /** "1. Add {G}" -> "Add {G}" for the Nth entry of an ability picker. */
    public static String stripOrdinal(String description, int zeroBasedIndex) {
        String prefix = (zeroBasedIndex + 1) + ". ";
        return description != null && description.startsWith(prefix) ? description.substring(prefix.length()) : description;
    }
}
