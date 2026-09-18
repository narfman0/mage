package mage.player.seat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Text clean-up for prompts: strip the engine's HTML and the " [58c]"
 * object-id suffixes it embeds in log names. Ported from mage-bench's
 * BridgePromptFormatting (MIT, Gregor Stocks). Rules text is the exception
 * ({@link #rules}): its markup is card information and travels as written.
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

    /**
     * Rules text as the engine wrote it. Its markup is the printed card's:
     * {@code <i>Channel</i> &mdash;} is an ability word, a trailing
     * {@code <i>(…)</i>} is reminder text, {@code <br>} a line break — the
     * client renders that vocabulary (and nothing else) rather than have it
     * stripped here and guessed back later. Only the object-id suffix, which
     * is ours, is dropped.
     */
    public static String rules(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return HEX_SUFFIX.matcher(s).replaceAll("");
    }

    public static List<String> rulesList(List<String> list) {
        if (list == null) {
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) {
            out.add(rules(s));
        }
        return out;
    }

    /** The engine's marker between a card's rules and its hints (HintUtils.HINT_START_MARK). */
    private static final String HINT_START = "<hintstart/>";
    private static final Pattern FONT_TAG = Pattern.compile("(?i)</?font[^>]*>");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final String[][] HINT_KINDS = {
        {"ICON_RESTRICT", "restrict"}, {"ICON_REQUIRE", "require"}, {"ICON_GOOD", "good"}, {"ICON_BAD", "bad"},
        {"ICON_DUNGEON_ROOM_CURRENT", "room_current"}, {"ICON_DUNGEON_ROOM_NEXT", "room_next"},
    };

    /** A card's rules split from its hints. */
    public record Split(List<String> rules, List<Map<String, Object>> hints) {
    }

    /**
     * The engine appends dynamic hints to a card's rules in a started game
     * (CardUtil.getCardRulesWithAdditionalInfo, PermanentImpl.getRules):
     * after a {@code <br/><hintstart/>} line come "Cards in your graveyard:
     * 3", "Can't attack (Pacifism)", "Goaded by …", some wrapped in a
     * {@code <font color=…>} and prefixed with an icon name
     * ({@code ICON_RESTRICT}, {@code ICON_GOOD}…), several joined by
     * {@code <br>}. Its Swing client renders them under the card in a
     * smaller font; passed through as rules they reached the board as
     * literal text (the engine-UI sweep, fullpod docs/engine-ui-surface.md,
     * 2026-09-18). Here the rules keep the printed markup ({@link #rules})
     * and each hint is one line of plain text with a {@code kind} from its
     * icon — the closest thing the engine has to saying why a card does or
     * doesn't do something.
     */
    public static Split splitRules(List<String> list) {
        if (list == null) {
            return new Split(null, List.of());
        }
        List<String> rules = new ArrayList<>();
        List<Map<String, Object>> hints = new ArrayList<>();
        boolean inHints = false;
        for (String s : list) {
            if (s == null) {
                continue;
            }
            if (s.contains(HINT_START)) {
                inHints = true;
                continue;
            }
            if (!inHints) {
                rules.add(rules(s));
                continue;
            }
            for (String line : BR.split(s)) {
                String text = FONT_TAG.matcher(line).replaceAll("");
                String kind = null;
                for (String[] k : HINT_KINDS) {
                    if (text.startsWith(k[0])) {
                        kind = k[1];
                        text = text.substring(k[0].length());
                        break;
                    }
                }
                text = stripHtml(text).trim();
                if (text.isEmpty()) {
                    continue;
                }
                Map<String, Object> hint = new LinkedHashMap<>();
                hint.put("text", text);
                if (kind != null) {
                    hint.put("kind", kind);
                }
                hints.add(hint);
            }
        }
        return new Split(rules, hints);
    }

    /** "1. Add {G}" -> "Add {G}" for the Nth entry of an ability picker. */
    public static String stripOrdinal(String description, int zeroBasedIndex) {
        String prefix = (zeroBasedIndex + 1) + ". ";
        return description != null && description.startsWith(prefix) ? description.substring(prefix.length()) : description;
    }
}
