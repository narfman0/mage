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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Always answer this the same way" (docs/board-ui.md "Remembered answers").
 * Two Soul's Attendants ask "you may gain 1 life?" every time a creature
 * enters — the question a pilot pays for and a person taps through. Each is
 * its own ability, so each is remembered once ({@code remember=ability});
 * from then on the engine answers them itself, the life is gained all the
 * same, and no further Bear costs a question — until the seat forgets, when
 * both ask again.
 */
public class RememberedAnswerTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";
    private static final String GAIN = "gain 1 life";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 300_000)
    public void aRememberedYesAnswersTheSameQuestionUntilItIsForgotten() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("remember", "duel", 5L, null,
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
        for (int i = 0; i < 2; i++) {
            perms.add(new PutToBattlefieldInfo(card("Soul's Attendant"), false));
        }
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            hand.add(card("Grizzly Bears"));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Forest"));
        }
        game.cheat(you.getId(), library, hand, perms, List.of(), List.of(), List.of());
        host.start();
        Set<String> remembered = new HashSet<>();
        int casts = 0;
        int asked = 0;
        int askedTwice = 0;
        int askedAfterForgetting = 0;
        boolean forgotten = false;
        try {
            for (int i = 0; i < 400; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args = new HashMap<>();
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args.put("choice", indexOfYou(d));
                } else if ("GAME_ASK".equals(type) && message.contains(GAIN)) {
                    asked++;
                    args.put("choice", "yes");
                    Object originalId = d.get("original_id");
                    Assert.assertNotNull("the asking ability rides on the question: " + d, originalId);
                    Assert.assertTrue("the auto-answer text is the question: " + d.get("auto_answer_text"),
                            String.valueOf(d.get("auto_answer_text")).contains(GAIN));
                    if (forgotten) {
                        askedAfterForgetting++;
                    } else if (!remembered.add(String.valueOf(originalId))) {
                        // The engine should have answered this one itself.
                        askedTwice++;
                    } else {
                        // Each Attendant is an ability of its own: "always for
                        // this ability" is remembered per card, as XMage means it.
                        args.put("remember", "ability");
                    }
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type"))
                        && d.get("combat_phase") == null && casts < 3 && castIndex(d) != null) {
                    if (casts == 2) {
                        // The third Bear is cast after the seat forgets, so both
                        // Attendants ask again: a memory, not a rule.
                        host.forgetAnswers("You");
                        forgotten = true;
                    }
                    casts++;
                    args.put("choice", castIndex(d));
                } else if (casts >= 3 && askedAfterForgetting >= 2 && "GAME_SELECT".equals(type)
                        && "select".equals(d.get("response_type"))) {
                    Assert.assertEquals("one question per Attendant, then none", 2, remembered.size());
                    Assert.assertEquals("a remembered question is never asked again", 0, askedTwice);
                    Assert.assertEquals("three Bears would have asked six times", 4, asked);
                    // Two Attendants, three Bears, every answer yes: six life.
                    Assert.assertEquals("the remembered yes gained the life all the same",
                            26, ((Number) GameHostTest.board(d).get("life")).intValue());
                    return;
                } else if ("GAME_TARGET".equals(type) && Boolean.TRUE.equals(d.get("required"))) {
                    // The cleanup discard: never one of the Bears this test casts.
                    args.put("choice", discardIndex(d));
                } else {
                    args.put("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never cast the three Bears (casts=" + casts + ", asked=" + asked + ")");
        } finally {
            host.end();
        }
    }

    @Test(timeout = 300_000)
    public void aRememberedTriggerOrderIsNotAskedAgain() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("remember-order", "duel", 5L, null,
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
        // Two different triggers on one creature entering, so the engine can't
        // merge them: the order is a question until it is remembered.
        perms.add(new PutToBattlefieldInfo(card("Soul Warden"), false));
        perms.add(new PutToBattlefieldInfo(card("Soul's Attendant"), false));
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            hand.add(card("Grizzly Bears"));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Forest"));
        }
        game.cheat(you.getId(), library, hand, perms, List.of(), List.of(), List.of());
        host.start();
        int casts = 0;
        int orders = 0;
        try {
            for (int i = 0; i < 400; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args = new HashMap<>();
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args.put("choice", indexOfYou(d));
                } else if ("GAME_TARGET".equals(type) && isTriggerOrder(d)) {
                    orders++;
                    Assert.assertEquals("the first Bear asks, the second doesn't", 1, orders);
                    args.put("choice", "0");
                    args.put("remember", "first");
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type"))
                        && d.get("combat_phase") == null && casts < 2 && castIndex(d) != null) {
                    casts++;
                    args.put("choice", castIndex(d));
                } else if (casts >= 2 && orders == 1 && "GAME_SELECT".equals(type) && "select".equals(d.get("response_type"))
                        && GameHostTest.board(d).get("life") != null
                        && ((Number) GameHostTest.board(d).get("life")).intValue() >= 22) {
                    // Both Bears are down and both Wardens have resolved: the
                    // order was asked once and the engine kept it.
                    return;
                } else if ("GAME_TARGET".equals(type) && Boolean.TRUE.equals(d.get("required"))) {
                    args.put("choice", discardIndex(d));
                } else {
                    args.put("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never cast the two Bears (casts=" + casts + ", orders=" + orders + ")");
        } finally {
            host.end();
        }
    }

    private static boolean isTriggerOrder(Map<String, Object> d) {
        List<Map<String, Object>> choices = ScriptedSeat.choices(d);
        return !choices.isEmpty() && choices.stream().allMatch(c -> "ability".equals(c.get("target_type")));
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

    private static String discardIndex(Map<String, Object> d) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if (!"Grizzly Bears".equals(c.get("name"))) {
                return String.valueOf(c.get("index"));
            }
        }
        return "0";
    }

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name);
        Assert.assertNotNull("card not in the DB: " + name, info);
        return info.createCard();
    }
}
