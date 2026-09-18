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

    @Test
    public void promptsAreFlattened() {
        Assert.assertEquals("Channel &mdash; {1}{G}, Discard this card: Destroy target artifact, enchantment, or nonbasic land an opponent controls.", Fmt.stripHtml(CHANNEL));
        Assert.assertEquals("Choose one: Draw a card", Fmt.stripHtml("Choose one<br>Draw a card"));
        Assert.assertEquals("Boseiju, Who Endures", Fmt.stripHtml("<b>Boseiju, Who Endures [a3f]</b>"));
    }
}
