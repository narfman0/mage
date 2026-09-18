package mage.player.seat;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/** Prompt text is flattened; rules text keeps the engine's markup (ability words, reminder text, line breaks). */
public class FmtTest {

    private static final String CHANNEL = "<i>Channel</i> &mdash; {1}{G}, Discard this card: Destroy target artifact, enchantment, or nonbasic land an opponent controls.";

    @Test
    public void rulesKeepMarkup() {
        Assert.assertEquals(CHANNEL, Fmt.rules(CHANNEL));
        Assert.assertEquals(
                "Flying <i>(This creature can't be blocked except by creatures with flying or reach.)</i>",
                Fmt.rules("Flying <i>(This creature can't be blocked except by creatures with flying or reach.)</i>"));
        Assert.assertEquals("Choose one &mdash;<br>&bull; Draw a card.", Fmt.rules("Choose one &mdash;<br>&bull; Draw a card."));
    }

    @Test
    public void rulesDropOnlyTheObjectId() {
        Assert.assertEquals("Equipped creature gets +2/+0. (Sword of Fire and Ice)", Fmt.rules("Equipped creature gets +2/+0. (Sword of Fire and Ice [58c])"));
        Assert.assertNull(Fmt.rules(null));
        Assert.assertEquals("", Fmt.rules(""));
        Assert.assertNull(Fmt.rulesList(null));
        Assert.assertEquals(List.of(CHANNEL, "Reach"), Fmt.rulesList(List.of(CHANNEL, "Reach")));
    }

    /** The engine's hints come after a marker line; each is plain text with a kind from its icon prefix. */
    @Test
    public void hintsAreSplitFromRules() {
        Fmt.Split split = Fmt.splitRules(List.of(
                "Delve <i>(Each card you exile from your graveyard while casting this spell pays for {1}.)</i>",
                "Draw three cards.",
                "<br/><hintstart/>",
                "Cards in your graveyard: 3",
                "ICON_RESTRICT<font color=#FF0000>Can't attack (Pacifism [a3f])</font>",
                "ICON_GOODYou control a Desert.<br>ICON_BADYou have a Desert card in your graveyard."));
        Assert.assertEquals(List.of(
                "Delve <i>(Each card you exile from your graveyard while casting this spell pays for {1}.)</i>",
                "Draw three cards."), split.rules());
        Assert.assertEquals(List.of(
                java.util.Map.of("text", "Cards in your graveyard: 3"),
                java.util.Map.of("text", "Can't attack (Pacifism)", "kind", "restrict"),
                java.util.Map.of("text", "You control a Desert.", "kind", "good"),
                java.util.Map.of("text", "You have a Desert card in your graveyard.", "kind", "bad")), split.hints());
        Assert.assertEquals(List.of("Reach"), Fmt.splitRules(List.of("Reach")).rules());
        Assert.assertTrue(Fmt.splitRules(List.of("Reach")).hints().isEmpty());
        Assert.assertNull(Fmt.splitRules(null).rules());
    }

    @Test
    public void promptsAreFlattened() {
        Assert.assertEquals("Channel &mdash; {1}{G}, Discard this card: Destroy target artifact, enchantment, or nonbasic land an opponent controls.", Fmt.stripHtml(CHANNEL));
        Assert.assertEquals("Choose one: Draw a card", Fmt.stripHtml("Choose one<br>Draw a card"));
        Assert.assertEquals("Boseiju, Who Endures", Fmt.stripHtml("<b>Boseiju, Who Endures [a3f]</b>"));
    }
}
