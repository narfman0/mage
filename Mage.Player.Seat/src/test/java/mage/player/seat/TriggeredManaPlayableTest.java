package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A spell only the bonus mana of a tap trigger can pay for is lit (fullpod
 * #92): Kinnan, Bonder Prodigy and an untapped Sylvan Caryatid are two mana
 * of any color, so Simic Signet ({2}) is a cast choice. The engine counted
 * the Caryatid's one mana and nothing from Kinnan, every card in hand was
 * unlit ("needs {2}, you can make {Any}"), and the server passed the whole
 * turn as an empty window.
 */
public class TriggeredManaPlayableTest {

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    @SuppressWarnings("unchecked")
    public void kinnanAndACaryatidLightSimicSignet() throws Exception {
        try (Fixture fx = new Fixture("kinnan-mana", true, false, f ->
                f.cheat(f.you, List.of(), List.of("Simic Signet"), List.of("Kinnan, Bonder Prodigy", "Sylvan Caryatid"), List.of()))) {
            Map<String, Object> d = fx.next("You");
            Assert.assertTrue("our main phase: " + d.get("context"), String.valueOf(d.get("context")).contains("YOUR_MAIN"));
            Assert.assertEquals("GAME_SELECT", d.get("action_type"));
            Fixture.index(d, "Simic Signet", "cast");
            Map<String, Object> signet = null;
            for (Map<String, Object> card : (List<Map<String, Object>>) GameHostTest.board(d).get("hand")) {
                if ("Simic Signet".equals(card.get("name"))) {
                    signet = card;
                }
            }
            Assert.assertNotNull("Simic Signet in hand: " + GameHostTest.board(d).get("hand"), signet);
            Assert.assertNull("no reason on a lit card: " + signet, signet.get("not_playable"));
            Assert.assertEquals("lit: " + signet, true, signet.get("playable"));
        }
    }
}
