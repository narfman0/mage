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
 * Permanent state the board used to drop: the X announced for a spell (on
 * the stack item) and the damage marked on a creature. Blaze for X=2 at our
 * own Colossal Dreadmaw, under full control so the seat sees its own spell
 * on the stack: the stack item says x=2; once it resolves the Dreadmaw
 * carries damage 2.
 */
public class PermanentStateTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void announcedXAndMarkedDamageReachTheBoard() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("permstate", "duel", 5L, null,
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
            perms.add(new PutToBattlefieldInfo(card("Mountain"), false));
        }
        perms.add(new PutToBattlefieldInfo(card("Colossal Dreadmaw"), false));
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Blaze")), perms, List.of(), List.of(), List.of());
        host.setFullControl("You", true);
        host.start();
        try {
            boolean cast = false;
            boolean sawX = false;
            for (int i = 0; i < 80; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_GET_AMOUNT".equals(type)) {
                    args = Map.of("amount", 2);
                } else if ("GAME_TARGET".equals(type) && cast) {
                    String dreadmaw = null;
                    for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                        if ("Colossal Dreadmaw".equals(c.get("name"))) {
                            dreadmaw = String.valueOf(c.get("id"));
                        }
                    }
                    Assert.assertNotNull("our Dreadmaw is a target: " + d, dreadmaw);
                    args = Map.of("choice", dreadmaw);
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d) != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(d));
                } else if (cast && "GAME_SELECT".equals(type) && d.get("stack") != null) {
                    // Our Blaze on the stack: the stack item carries the announced X.
                    @SuppressWarnings("unchecked")
                    Map<String, Object> top = ((List<Map<String, Object>>) d.get("stack")).get(0);
                    Assert.assertEquals("Blaze", top.get("name"));
                    Assert.assertEquals(2, top.get("x"));
                    sawX = true;
                    args = Map.of("choice", "no");
                } else if (cast && sawX && "GAME_SELECT".equals(type)) {
                    // Resolved: two damage marked on the Dreadmaw, nothing phased out.
                    Map<String, Object> board = GameHostTest.board(d);
                    Map<String, Object> dreadmaw = null;
                    for (Object o : (List<?>) board.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        if ("Colossal Dreadmaw".equals(perm.get("name"))) {
                            dreadmaw = perm;
                        }
                        Assert.assertNull(perm.get("phased_out"));
                        Assert.assertNull(perm.get("x"));
                    }
                    Assert.assertNotNull(dreadmaw);
                    Assert.assertEquals("two damage marked: " + dreadmaw, 2, dreadmaw.get("damage"));
                    return;
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never saw the Blaze resolve");
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
            if ("cast".equals(c.get("action")) && "Blaze".equals(c.get("name"))) {
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
