package mage.player.seat;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.players.Player;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The priority window right after the seat's own cast. XMage's client passes
 * it by default (its {@code passPriorityCast} preference) and so does the
 * seat (owner decision, 2026-09-18): with an instant in hand, the next
 * question after casting a creature comes once the creature has resolved.
 * Under full control the seat is asked with its own spell on the stack —
 * that is how a person holds priority to respond to their own spell.
 */
public class OwnCastWindowTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void theWindowAfterYourOwnCastIsPassedByDefault() throws Exception {
        Map<String, Object> next = afterCasting(false);
        Assert.assertNull("no question with the seat's own spell on the stack: " + next, next.get("stack"));
    }

    @Test(timeout = 240_000)
    public void fullControlAsksWithYourOwnSpellOnTheStack() throws Exception {
        Map<String, Object> next = afterCasting(true);
        Assert.assertEquals("GAME_SELECT", next.get("action_type"));
        Assert.assertNotNull("the seat's own spell is on the stack: " + next, next.get("stack"));
        Assert.assertTrue(String.valueOf(next.get("stack")).contains("Grizzly Bears"));
    }

    /** Three Forests, Grizzly Bears and Giant Growth in hand; cast the Bears and return the decision that follows. */
    private Map<String, Object> afterCasting(boolean fullControl) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("owncast", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<PutToBattlefieldInfo> perms = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            perms.add(new PutToBattlefieldInfo(card("Forest"), false));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Grizzly Bears"), card("Giant Growth")), perms, List.of(), List.of(), List.of());
        host.setFullControl("You", fullControl);
        host.start();
        try {
            boolean cast = false;
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over before the cast: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                if (cast) {
                    return d;
                }
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d) != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(d));
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never cast Grizzly Bears");
        } finally {
            host.end();
        }
    }

    private static String indexOfYou(Map<String, Object> d) {
        List<Map<String, Object>> choices = ScriptedSeat.choices(d);
        for (int i = 0; i < choices.size(); i++) {
            if (Boolean.TRUE.equals(choices.get(i).get("is_you"))) {
                return String.valueOf(i);
            }
        }
        return "0";
    }

    private static String castIndex(Map<String, Object> d) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("cast".equals(c.get("action")) && "Grizzly Bears".equals(c.get("name"))) {
                return String.valueOf(c.get("index"));
            }
        }
        return null;
    }

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name);
        Assert.assertNotNull("card not in the DB: " + name, info);
        return info.createCard();
    }
}
