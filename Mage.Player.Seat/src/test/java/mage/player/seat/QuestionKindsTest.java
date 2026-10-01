package mage.player.seat;

import mage.abilities.keyword.IndestructibleAbility;
import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.game.permanent.Permanent;
import mage.players.Player;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The questions a scripted game never asks (fullpod issue #23), one
 * targeted game each: a seat with the card in hand and the lands to cast it
 * against a CPU that has nothing to do. Each asserts the question renders
 * as the board reads it and that an answer other than the default is
 * accepted and takes effect.
 *
 * <ul>
 *   <li>piles — Fact or Fiction: the CPU separates, the seat picks a pile</li>
 *   <li>an amount — Harnessed Lightning: how much energy to pay</li>
 *   <li>modes — Boros Charm: one of three modes</li>
 *   <li>an X cost — Blaze: X announced, then the target</li>
 * </ul>
 */
public class QuestionKindsTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    /**
     * Fact or Fiction: the CPU opponent splits the top five into two piles,
     * the seat is asked which pile it takes. Both piles are rendered with
     * their cards' ids, five cards in all; the seat takes pile 2 (the
     * default answer is pile 1), and pile 2 is what lands in its hand while
     * pile 1 goes to the graveyard.
     */
    @Test(timeout = 300_000)
    public void aPileIsChosenAndThatPileIsDrawn() throws Exception {
        GameHost host = game("piles", "Island", 4, "Fact or Fiction");
        try {
            Map<String, Object>[] pileQuestion = new Map[1];
            play(host, "Fact or Fiction", d -> {
                if ("GAME_CHOOSE_PILE".equals(d.get("action_type"))) {
                    Assert.assertEquals("pile", d.get("response_type"));
                    List<Map<String, Object>> pile1 = list(d.get("pile1"));
                    List<Map<String, Object>> pile2 = list(d.get("pile2"));
                    Assert.assertEquals("five cards between the piles: " + d, 5, pile1.size() + pile2.size());
                    for (Map<String, Object> c : concat(pile1, pile2)) {
                        Assert.assertNotNull("a pile card has an id: " + c, c.get("id"));
                        Assert.assertNotNull("and a name: " + c, c.get("name"));
                    }
                    pileQuestion[0] = d;
                    return Map.of("pile", 2);
                }
                return null;
            });
            Assert.assertNotNull("never asked to choose a pile", pileQuestion[0]);
            Set<String> pile1 = ids(list(pileQuestion[0].get("pile1")));
            Set<String> pile2 = ids(list(pileQuestion[0].get("pile2")));
            Set<String> hand = ids(list(you(host).get("hand")));
            Assert.assertTrue("pile 2 " + pile2 + " is in the hand " + hand, hand.containsAll(pile2));
            for (String id : pile1) {
                Assert.assertFalse("pile 1's " + id + " is not drawn: " + hand, hand.contains(id));
            }
            Assert.assertEquals("pile 1 went to the graveyard", pile1.size() + 1 /* the spell */,
                    host.game().getPlayer(youId(host)).getGraveyard().size());
        } finally {
            host.end();
        }
    }

    /**
     * Harnessed Lightning: the seat gets three energy and is asked how much
     * to pay, an amount between 0 and 3. It pays 2 (the default is the
     * minimum): its Bear takes 2 and dies, and one energy is left.
     */
    @Test(timeout = 300_000)
    public void anAmountIsAnsweredAndThatMuchIsPaid() throws Exception {
        GameHost host = game("amount", "Mountain", 2, "Harnessed Lightning", "Grizzly Bears");
        try {
            boolean[] asked = new boolean[1];
            play(host, "Harnessed Lightning", d -> {
                if ("GAME_GET_AMOUNT".equals(d.get("action_type"))) {
                    Assert.assertEquals("amount", d.get("response_type"));
                    Assert.assertEquals(0, d.get("min"));
                    Assert.assertEquals(3, d.get("max"));
                    Assert.assertNull("no mana bound on a non-X amount: " + d, d.get("mana_available"));
                    asked[0] = true;
                    return Map.of("amount", 2);
                }
                if ("GAME_TARGET".equals(d.get("action_type")) && !asked[0]) {
                    return Map.of("choice", byName(d, "Grizzly Bears"));
                }
                return null;
            });
            Assert.assertTrue("never asked for an amount", asked[0]);
            Player you = host.game().getPlayer(youId(host));
            Assert.assertEquals("3 energy, 2 paid", 1, you.getCountersCount(CounterType.ENERGY));
            Assert.assertTrue("the Bear took 2 and died",
                    host.game().getBattlefield().getAllActivePermanents(you.getId()).stream()
                            .noneMatch(p -> "Grizzly Bears".equals(p.getName())));
        } finally {
            host.end();
        }
    }

    /**
     * Boros Charm: one mode of three, each listed with its text and no
     * "1." in front, and Cancel last. The seat takes the second (the default is the first):
     * its Bear gains indestructible and nobody is dealt 4.
     */
    @Test(timeout = 300_000)
    public void aModeIsChosenAndThatModeResolves() throws Exception {
        GameHost host = gameWith("modes", List.of("Mountain", "Plains", "Grizzly Bears"), "Boros Charm");
        try {
            boolean[] asked = new boolean[1];
            play(host, "Boros Charm", d -> {
                if ("GAME_CHOOSE_ABILITY".equals(d.get("action_type"))) {
                    List<Map<String, Object>> modes = ScriptedSeat.choices(d);
                    // Three modes, then the engine's Cancel (the cast is taken back).
                    Assert.assertEquals("three modes and Cancel: " + modes, 4, modes.size());
                    Assert.assertEquals("Cancel", modes.get(3).get("description"));
                    String indestructible = null;
                    for (int i = 0; i < modes.size(); i++) {
                        String text = String.valueOf(modes.get(i).get("description"));
                        Assert.assertEquals(i, modes.get(i).get("index"));
                        Assert.assertFalse("no ordinal: " + text, text.matches("^\\d+\\..*"));
                        if (text.contains("indestructible")) {
                            indestructible = String.valueOf(i);
                        }
                    }
                    Assert.assertEquals("the second mode: " + modes, "1", indestructible);
                    asked[0] = true;
                    return Map.of("choice", indestructible);
                }
                return null;
            });
            Assert.assertTrue("never asked for a mode", asked[0]);
            Game game = host.game();
            Permanent bear = game.getBattlefield().getAllActivePermanents(youId(host)).stream()
                    .filter(p -> "Grizzly Bears".equals(p.getName())).findFirst().orElse(null);
            Assert.assertNotNull(bear);
            Assert.assertTrue("the Bear is indestructible this turn", bear.hasAbility(IndestructibleAbility.getInstance(), game));
            for (Player p : game.getPlayers().values()) {
                Assert.assertEquals(p.getName() + " was not dealt 4", 20, p.getLife());
            }
        } finally {
            host.end();
        }
    }

    /**
     * Blaze: X is announced first, as an amount question that names {X} and
     * says how much mana the seat could make (four Mountains); the seat
     * announces 3, targets the CPU, the cost is paid, and the CPU is dealt 3.
     */
    @Test(timeout = 300_000)
    public void anXIsAnnouncedAndThatMuchIsDealt() throws Exception {
        GameHost host = game("x-cost", "Mountain", 4, "Blaze");
        try {
            boolean[] asked = new boolean[1];
            play(host, "Blaze", d -> {
                if ("GAME_GET_AMOUNT".equals(d.get("action_type"))) {
                    Assert.assertTrue("names {X}: " + d.get("message"), String.valueOf(d.get("message")).contains("X"));
                    Assert.assertEquals(0, d.get("min"));
                    Assert.assertEquals("four Mountains: " + d, 4, d.get("mana_available"));
                    Assert.assertEquals(4, d.get("max"));
                    asked[0] = true;
                    return Map.of("amount", 3);
                }
                if ("GAME_TARGET".equals(d.get("action_type")) && asked[0]) {
                    return Map.of("choice", byName(d, "CPU"));
                }
                return null;
            });
            Assert.assertTrue("never asked for X", asked[0]);
            for (Player p : host.game().getPlayers().values()) {
                Assert.assertEquals(p.getName(), "CPU".equals(p.getName()) ? 17 : 20, p.getLife());
            }
        } finally {
            host.end();
        }
    }

    // ---- the game -------------------------------------------------------

    private static GameHost game(String id, String land, int lands, String spell, String... others) throws Exception {
        List<String> perms = new ArrayList<>();
        for (int i = 0; i < lands; i++) {
            perms.add(land);
        }
        perms.addAll(List.of(others));
        return gameWith(id, perms, spell);
    }

    /** The seat on the play with {@code perms} in play and {@code spell} in hand; seven Dreadmaws to draw. */
    private static GameHost gameWith(String id, List<String> perms, String spell) throws Exception {
        GameHost host = new GameHost(new GameHost.Config(id, "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
        Game game = host.game();
        List<PutToBattlefieldInfo> battlefield = new ArrayList<>();
        for (String name : perms) {
            battlefield.add(new PutToBattlefieldInfo(card(name), false));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(youId(host), library, List.of(card(spell)), battlefield, List.of(), List.of(), List.of());
        host.start();
        return host;
    }

    /**
     * Takes the play, casts {@code spell} at the first window, answers every
     * question {@code script} knows (null: not this one) and passes the rest,
     * until the spell has resolved and the seat holds priority again with an
     * empty stack.
     */
    private static void play(GameHost host, String spell, Function<Map<String, Object>, Map<String, Object>> script) throws Exception {
        boolean cast = false;
        for (int i = 0; i < 80; i++) {
            Map<String, Object> d = host.awaitDecision("You", 120_000);
            Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
            Assert.assertNull("render error: " + d, d.get("error"));
            String type = String.valueOf(d.get("action_type"));
            String message = String.valueOf(d.get("message"));
            Map<String, Object> args;
            if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                args = Map.of("choice", byYou(d));
            } else if (cast && (args = script.apply(d)) != null) {
                // answered by the scenario
            } else if (!cast && "GAME_SELECT".equals(type) && "select".equals(d.get("response_type"))
                    && d.get("combat_phase") == null && castId(d, spell) != null) {
                cast = true;
                args = Map.of("choice", castId(d, spell));
            } else if (cast && "GAME_SELECT".equals(type) && d.get("stack") == null) {
                return; // resolved: priority again on an empty stack
            } else {
                Assert.assertFalse("a mana prompt the seat should have paid itself: " + d,
                        type.startsWith("GAME_PLAY_"));
                args = Map.of("choice", "no");
            }
            Map<String, Object> answer = host.chooseAction("You", args);
            Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
        }
        throw new AssertionError(spell + " never resolved");
    }

    private static java.util.UUID youId(GameHost host) {
        for (Player p : host.game().getPlayers().values()) {
            if ("You".equals(p.getName())) {
                return p.getId();
            }
        }
        throw new AssertionError("no seat named You");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> you(GameHost host) {
        for (Object seat : (List<?>) host.state("You").get("board")) {
            Map<String, Object> board = (Map<String, Object>) seat;
            if (Boolean.TRUE.equals(board.get("is_you"))) {
                return board;
            }
        }
        throw new AssertionError("no board for You");
    }

    private static String byYou(Map<String, Object> d) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if (Boolean.TRUE.equals(c.get("is_you"))) {
                return String.valueOf(c.get("index"));
            }
        }
        return "0";
    }

    private static String byName(Map<String, Object> d, String name) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if (name.equals(c.get("name"))) {
                return String.valueOf(c.get("id"));
            }
        }
        throw new AssertionError(name + " is not offered: " + d.get("choices"));
    }

    private static String castId(Map<String, Object> d, String name) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("cast".equals(c.get("action")) && name.equals(c.get("name"))) {
                return String.valueOf(c.get("index"));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static List<Map<String, Object>> concat(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        List<Map<String, Object>> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static Set<String> ids(List<Map<String, Object>> cards) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> c : cards) {
            out.add(String.valueOf(c.get("id")));
        }
        return out;
    }

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name);
        Assert.assertNotNull("card not in the DB: " + name, info);
        return info.createCard();
    }
}
