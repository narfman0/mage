package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * A card castable from the graveyard (Think Twice's flashback) or from
 * exile (Bonecrusher Giant after its adventure, Stomp) is a cast from
 * there — {@code action: cast, from: graveyard|exile}, the way it is cast
 * as {@code ability} in the cost's place — and the card is lit in that
 * zone on the board. Before (2026-09-18) both rendered as a bare
 * "activate" with no cost and no zone, and nothing on the board lit.
 */
public class ZonePlayTest {

    private static final Logger LOG = Logger.getLogger(ZonePlayTest.class);
    static final String DECK = "src/test/resources/decks/flashback_adventure.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new java.util.ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 300_000)
    public void flashbackAndAdventureAreCastsFromTheirZones() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("zoneplay", "duel", 4L, null,
                List.of(new GameHost.SeatSpec("You", "seat", DECK, 0), new GameHost.SeatSpec("CPU", "cpu", GameHostTest.BEARS, 6)), false));
        ScriptedSeat script = new ScriptedSeat();
        boolean sawFlashback = false;
        boolean sawAdventureReturn = false;
        try {
            host.start();
            for (int i = 0; i < 600 && !(sawFlashback && sawAdventureReturn); i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error", d.get("error"));
                List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                Map<String, Object> args = null;
                for (Map<String, Object> c : choices) {
                    if ("graveyard".equals(c.get("from"))) {
                        Assert.assertEquals("Think Twice", c.get("name"));
                        Assert.assertEquals("cast", c.get("action"));
                        Assert.assertTrue("the flashback line as the cost: " + c, String.valueOf(c.get("ability")).startsWith("Flashback {2}{U}"));
                        Assert.assertNull("the printed cost is not what is paid: " + c, c.get("mana_cost"));
                        Assert.assertTrue("lit in the graveyard: " + GameHostTest.board(d).get("graveyard"), litIn(GameHostTest.board(d), "graveyard", String.valueOf(c.get("id"))));
                        sawFlashback = true;
                        args = Map.of("choice", String.valueOf(c.get("index")));
                    }
                    if ("exile".equals(c.get("from"))) {
                        Assert.assertEquals("Bonecrusher Giant", c.get("name"));
                        Assert.assertEquals("cast", c.get("action"));
                        Assert.assertEquals("the creature's own cost from exile: " + c, "{2}{R}", c.get("mana_cost"));
                        Assert.assertTrue("lit in exile", litIn(GameHostTest.board(d), "exile", String.valueOf(c.get("id"))));
                        LOG.info("adventure return choice: " + c);
                        sawAdventureReturn = true;
                        args = Map.of("choice", String.valueOf(c.get("index")));
                    }
                }
                if (args == null && "GAME_CHOOSE_ABILITY".equals(d.get("action_type"))) {
                    // "Choose spell or ability to play" (both halves affordable): the
                    // adventure, Stomp. With two lands only Stomp is castable and the
                    // engine picks it without asking.
                    for (Map<String, Object> c : choices) {
                        if (String.valueOf(c.get("name")).contains("Stomp")) {
                            args = Map.of("choice", String.valueOf(c.get("index")));
                        }
                    }
                }
                if (args == null) {
                    args = script.answer(d);
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Assert.assertTrue("a flashback offered from the graveyard", sawFlashback);
        Assert.assertTrue("the Giant offered from exile after its adventure", sawAdventureReturn);
    }

    @SuppressWarnings("unchecked")
    private static boolean litIn(Map<String, Object> player, String zone, String id) {
        Object cards = player.get(zone);
        if (!(cards instanceof List<?> l)) {
            return false;
        }
        for (Object o : l) {
            Map<String, Object> c = (Map<String, Object>) o;
            if (id.equals(c.get("id"))) {
                return Boolean.TRUE.equals(c.get("playable"));
            }
        }
        return false;
    }
}
