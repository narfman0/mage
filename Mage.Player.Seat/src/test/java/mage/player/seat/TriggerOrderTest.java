package mage.player.seat;

import mage.abilities.TriggeredAbility;
import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.players.Player;
import mage.util.ShortIdRegistry;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two triggers of one name are listed, and get their short ids, in a stable
 * order (fullpod issue #33): by their source's short id, then the order the
 * engine lists them. They used to come off a hash set of their random
 * UUIDs, so two runs of the same game swapped two Soul Warden triggers' ids
 * and indexes, and a browser's index answer could not replay.
 *
 * Two Soul Wardens and a Soul's Attendant see a Grizzly Bears enter: three
 * triggers, two named Soul Warden, and the trigger-order question.
 */
public class TriggerOrderTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 300_000)
    public void twoTriggersOfOneNameAreListedBySourceTheSameEveryRun() throws Exception {
        List<String> first = triggerOrder("trigger-order-1");
        List<String> second = triggerOrder("trigger-order-2");
        Assert.assertEquals("the same game lists its triggers the same way twice", first, second);
    }

    /**
     * One game: the trigger-order question as "index id name source-seq"
     * lines, after asserting the order within it. Answered by index 1 (the
     * second Soul Warden trigger), which is the one put on the stack.
     */
    private static List<String> triggerOrder(String gameId) throws Exception {
        GameHost host = new GameHost(new GameHost.Config(gameId, "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
        Game game = host.game();
        UUID you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p.getId();
            }
        }
        Assert.assertNotNull(you);
        List<PutToBattlefieldInfo> perms = new ArrayList<>();
        for (String name : List.of("Forest", "Forest", "Soul Warden", "Soul Warden", "Soul's Attendant")) {
            perms.add(new PutToBattlefieldInfo(card(name), false));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you, library, List.of(card("Grizzly Bears")), perms, List.of(), List.of(), List.of());
        host.start();
        ShortIdRegistry ids = game.getShortIdRegistry();
        try {
            boolean cast = false;
            for (int i = 0; i < 80; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error: " + d, d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", byYou(choices));
                } else if (cast && "GAME_TARGET".equals(type) && message.startsWith("Pick triggered ability")) {
                    Assert.assertEquals("three triggers: " + choices, 3, choices.size());
                    List<String> order = new ArrayList<>();
                    List<TriggeredAbility> triggered = game.getState().getTriggered(you);
                    String lastName = null;
                    int lastSource = -1;
                    int lastId = -1;
                    for (Map<String, Object> c : choices) {
                        String id = String.valueOf(c.get("id"));
                        UUID abilityId = ids.tryResolve(id);
                        TriggeredAbility ability = triggered.stream().filter(t -> t.getId().equals(abilityId)).findFirst().orElse(null);
                        Assert.assertNotNull("choice " + id + " is a waiting trigger", ability);
                        String name = String.valueOf(c.get("name"));
                        int source = ids.getSequence(ability.getSourceId());
                        int seq = ShortIdRegistry.parseSequence(id);
                        if (name.equals(lastName)) {
                            Assert.assertTrue("two " + name + " triggers by their source's short id: " + choices, source > lastSource);
                            Assert.assertTrue("and handed their own ids in that order: " + choices, seq > lastId);
                        }
                        lastName = name;
                        lastSource = source;
                        lastId = seq;
                        order.add(c.get("index") + " " + id + " " + name + " p" + source);
                    }
                    Assert.assertEquals("Soul Warden", choices.get(0).get("name"));
                    Assert.assertEquals("Soul Warden", choices.get(1).get("name"));
                    String picked = String.valueOf(choices.get(1).get("id"));
                    Map<String, Object> answer = host.chooseAction("You", Map.of("choice", "1"));
                    Assert.assertTrue("index answer rejected: " + answer, Boolean.TRUE.equals(answer.get("success")));
                    // The trigger at index 1 is the one on the stack when the
                    // remaining two are asked about.
                    Map<String, Object> next = host.awaitDecision("You", 120_000);
                    Assert.assertEquals("the next trigger-order question: " + next, 2, ScriptedSeat.choices(next).size());
                    Object stack = next.get("stack");
                    Assert.assertTrue("the second Soul Warden trigger went on the stack first: " + stack,
                            stack instanceof List<?> l && l.size() == 1 && picked.equals(String.valueOf(((Map<?, ?>) l.get(0)).get("id"))));
                    return order;
                } else if (!cast && "GAME_SELECT".equals(type) && "select".equals(d.get("response_type"))
                        && d.get("combat_phase") == null && castIndex(choices) != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(choices));
                } else if ("GAME_TARGET".equals(type) && message.startsWith("Pick triggered ability")) {
                    args = Map.of("choice", "0"); // the cheated-in creatures saw each other enter
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never asked to order the triggers");
        } finally {
            host.end();
        }
    }

    private static String byYou(List<Map<String, Object>> choices) {
        for (Map<String, Object> c : choices) {
            if (Boolean.TRUE.equals(c.get("is_you"))) {
                return String.valueOf(c.get("index"));
            }
        }
        return "0";
    }

    private static String castIndex(List<Map<String, Object>> choices) {
        for (Map<String, Object> c : choices) {
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
