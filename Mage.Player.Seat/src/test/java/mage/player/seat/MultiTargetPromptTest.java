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
 * A prompt for several targets comes round once per pick. What the seat
 * says on the second round: which targets are picked already (the engine's
 * chosenTargets) and that the right button now reads "Done", not Cancel.
 * Frost Breath: up to two target creatures.
 */
public class MultiTargetPromptTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void theSecondRoundListsTheFirstPickAndOffersDone() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("multitarget", "duel", 5L, null,
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
            perms.add(new PutToBattlefieldInfo(card("Island"), false));
        }
        perms.add(new PutToBattlefieldInfo(card("Grizzly Bears"), false));
        perms.add(new PutToBattlefieldInfo(card("Grizzly Bears"), false));
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Frost Breath")), perms, List.of(), List.of(), List.of());
        host.start();
        try {
            boolean cast = false;
            String firstPick = null;
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_TARGET".equals(type) && cast && firstPick == null) {
                    // The first round: nothing chosen yet, every Bear offered, and the
                    // prompt says which card is asking (the engine's second message).
                    Assert.assertNull("nothing chosen on the first round: " + d, d.get("chosen"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> source = (Map<String, Object>) d.get("source");
                    Assert.assertNotNull("the source of the prompt: " + d, source);
                    Assert.assertEquals("Frost Breath", source.get("name"));
                    Assert.assertNotNull("resolved to the spell's id: " + source, source.get("id"));
                    Assert.assertEquals(Boolean.TRUE, d.get("can_cancel"));
                    List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                    Assert.assertEquals("two Bears offered: " + choices, 2, choices.size());
                    firstPick = String.valueOf(choices.get(0).get("id"));
                    args = Map.of("choice", firstPick);
                } else if ("GAME_TARGET".equals(type) && cast) {
                    // The second round: the pick is listed, the button is Done, and
                    // the engine offers only the other Bear (a permanent target's
                    // possibleTargets leaves out what is already chosen).
                    Assert.assertEquals(List.of(firstPick), d.get("chosen"));
                    Assert.assertEquals("Done", d.get("done_text"));
                    Assert.assertEquals(Boolean.TRUE, d.get("can_cancel"));
                    List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                    Assert.assertEquals("the other Bear: " + choices, 1, choices.size());
                    Assert.assertNotEquals(firstPick, choices.get(0).get("id"));
                    Assert.assertNull(choices.get(0).get("chosen"));
                    Map<String, Object> answer = host.chooseAction("You", Map.of("choice", "no"));
                    Assert.assertTrue("Done rejected: " + answer, Boolean.TRUE.equals(answer.get("success")));
                    return;
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d) != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(d));
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never reached the second target round");
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
            if ("cast".equals(c.get("action")) && "Frost Breath".equals(c.get("name"))) {
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
