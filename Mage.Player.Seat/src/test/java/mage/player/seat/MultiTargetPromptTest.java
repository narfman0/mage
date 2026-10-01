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
                    // the first Bear is offered again, flagged chosen (picking it
                    // again takes it back; the engine's possibleTargets leaves it
                    // out, the seat adds it — fullpod issue #34).
                    Assert.assertEquals(List.of(firstPick), d.get("chosen"));
                    Assert.assertEquals("Done", d.get("done_text"));
                    Assert.assertEquals(Boolean.TRUE, d.get("can_cancel"));
                    List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                    Assert.assertEquals("both Bears: " + choices, 2, choices.size());
                    for (Map<String, Object> c : choices) {
                        Assert.assertEquals(c.toString(), firstPick.equals(String.valueOf(c.get("id"))) ? Boolean.TRUE : null, c.get("chosen"));
                    }
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

    /**
     * Divided damage (Arc Lightning, 3 among one to three targets): a pick
     * is offered again on the next round, flagged chosen, and picking it
     * again takes it back — before fullpod issue #34 it was not on the list
     * and the seat refused it. Picked, taken back, the other Bear picked and
     * Done: the other Bear takes all 3 and the first is untouched.
     */
    @Test(timeout = 240_000)
    public void aDividedDamagePickCanBeTakenBack() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("divide-takeback", "duel", 5L, null,
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
        perms.add(new PutToBattlefieldInfo(card("Grizzly Bears"), false));
        perms.add(new PutToBattlefieldInfo(card("Grizzly Bears"), false));
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Arc Lightning")), perms, List.of(), List.of(), List.of());
        host.start();
        try {
            boolean cast = false;
            String firstId = null;
            String secondId = null;
            int round = 0;
            for (int i = 0; i < 80; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_TARGET".equals(type) && cast && message.contains("divide")) {
                    round++;
                    List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                    if (firstId == null) {
                        List<String> bears = new ArrayList<>();
                        for (Map<String, Object> c : choices) {
                            if ("Grizzly Bears".equals(c.get("name"))) {
                                bears.add(String.valueOf(c.get("id")));
                            }
                        }
                        Assert.assertEquals("two Bears offered: " + choices, 2, bears.size());
                        firstId = bears.get(0);
                        secondId = bears.get(1);
                    }
                    if (round == 1) {
                        Assert.assertNull("nothing chosen on the first round: " + d, d.get("chosen"));
                        args = Map.of("choice", firstId);
                    } else if (round == 2) {
                        // The pick is on the list again, flagged; picking it takes it back.
                        Assert.assertEquals(List.of(firstId), d.get("chosen"));
                        Map<String, Object> again = byId(choices, firstId);
                        Assert.assertNotNull("the chosen Bear is offered again: " + choices, again);
                        Assert.assertEquals(Boolean.TRUE, again.get("chosen"));
                        args = Map.of("choice", firstId);
                    } else if (round == 3) {
                        Assert.assertNull("the pick was taken back: " + d, d.get("chosen"));
                        Assert.assertNull(byId(choices, firstId).get("chosen"));
                        args = Map.of("choice", secondId);
                    } else {
                        Assert.assertEquals(List.of(secondId), d.get("chosen"));
                        args = Map.of("choice", "no"); // Done: one target takes all 3
                    }
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d, "Arc Lightning") != null && !cast) {
                    cast = true;
                    args = Map.of("choice", castIndex(d, "Arc Lightning"));
                } else if (cast && round >= 4) {
                    break; // the spell is on the stack; let it resolve below
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            Assert.assertTrue("reached Done", round >= 4);
            // Pass until the spell has resolved: one Bear dies, the one taken back lives.
            for (int i = 0; i < 20 && bearsOnBoard(host).size() > 1; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                host.chooseAction("You", Map.of("choice", "no"));
            }
            Assert.assertEquals("the second Bear took all 3, the first was taken back", List.of(firstId), bearsOnBoard(host));
        } finally {
            host.end();
        }
    }

    /** The short ids of the Grizzly Bears on the seat's own battlefield. */
    @SuppressWarnings("unchecked")
    private static List<String> bearsOnBoard(GameHost host) {
        List<String> out = new ArrayList<>();
        for (Object seat : (List<?>) host.state("You").get("board")) {
            Map<String, Object> board = (Map<String, Object>) seat;
            if (!Boolean.TRUE.equals(board.get("is_you"))) {
                continue;
            }
            for (Object o : (List<?>) board.get("battlefield")) {
                Map<String, Object> perm = (Map<String, Object>) o;
                if ("Grizzly Bears".equals(perm.get("name"))) {
                    out.add(String.valueOf(perm.get("id")));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> byId(List<Map<String, Object>> choices, String id) {
        for (Map<String, Object> c : choices) {
            if (id.equals(String.valueOf(c.get("id")))) {
                return c;
            }
        }
        return null;
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
        return castIndex(d, "Frost Breath");
    }

    private static String castIndex(Map<String, Object> d, String name) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("cast".equals(c.get("action")) && name.equals(c.get("name"))) {
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
