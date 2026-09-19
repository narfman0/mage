package mage.player.seat;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.game.permanent.Permanent;
import mage.players.Player;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Why an unlit card is unlit, on the cards themselves and through the real
 * engine: a cheated board and hand, and the {@code not_playable} the seat
 * derives at its own priority window ({@link Unlit}). The owner's case
 * (2026-09-18) is the first two — Survival of the Fittest with {@code {G}}
 * up and no creature card in hand, and the same board with one.
 */
public class UnlitReasonTest {

    private static final Logger LOG = Logger.getLogger(UnlitReasonTest.class);
    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";
    /** Seven of these are the opening hand, so what the hand holds is the test's. */
    private static final String NO_CREATURE = "Plains";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    /**
     * The first priority window of ours whose context carries {@code marker}
     * ("YOUR_MAIN" for our own main phase, "Begin Combat" for a step where
     * only instants are legal), with {@code library} stacked for the opening
     * hand, {@code untapped} and {@code tapped} permanents under our control.
     * Every other question is declined; the starting-player pick is us.
     */
    private Map<String, Object> window(String marker, String library, List<String> untapped, List<String> tapped) throws Exception {
        return window(marker, library, untapped, tapped, null, null);
    }

    /**
     * The same, with {@code duringTheTurn} run at the first priority window
     * and {@code castFirst} cast there, the search for {@code marker}
     * starting only after them: the game's first untap step unteps whatever
     * the cheat tapped, and putting a permanent down by cheat removes its
     * summoning sickness — so a tapped source and a creature that arrived
     * this turn are made during the turn, one by hand and one by casting it.
     */
    private Map<String, Object> window(String marker, String library, List<String> untapped, List<String> tapped,
            BiConsumer<Game, Player> duringTheTurn, String castFirst) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("unlit", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0),
                        new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), true));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<PutToBattlefieldInfo> perms = new ArrayList<>();
        for (String name : untapped) {
            perms.add(new PutToBattlefieldInfo(card(name), false));
        }
        for (String name : tapped) {
            perms.add(new PutToBattlefieldInfo(card(name), true));
        }
        List<Card> deck = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            deck.add(card(library));
        }
        game.cheat(you.getId(), deck, List.of(), perms, List.of(), List.of(), List.of());
        host.start();
        boolean changed = false;
        boolean cast = false;
        try {
            for (int i = 0; i < 40; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                String context = String.valueOf(d.get("context"));
                boolean priority = "GAME_SELECT".equals(type) && d.get("combat_phase") == null;
                boolean ready = (duringTheTurn == null || changed) && (castFirst == null || cast);
                String castIndex = priority && castFirst != null && !cast ? castIndex(d, castFirst) : null;
                if (priority && duringTheTurn != null && !changed) {
                    duringTheTurn.accept(game, you);
                    changed = true;
                } else if (priority && ready && context.contains(marker)) {
                    LOG.info(marker + " window: " + context + " " + GameHostTest.board(d));
                    return d;
                }
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if (castIndex != null) {
                    cast = true;
                    args = Map.of("choice", castIndex);
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        throw new AssertionError("no " + marker + " window in the first 40 decisions");
    }

    @Test(timeout = 240_000)
    public void survivalWithNoCreatureInHandNamesTheCostItCannotPay() throws Exception {
        Map<String, Object> d = window("YOUR_MAIN", NO_CREATURE, List.of("Survival of the Fittest", "Forest"), List.of());
        Assert.assertEquals("can't pay: discard a creature card", reason(card(d, "battlefield", "Survival of the Fittest")));
    }

    @Test(timeout = 240_000)
    public void survivalWithACreatureInHandIsLitAndUnexplained() throws Exception {
        Map<String, Object> d = window("YOUR_MAIN", "Grizzly Bears", List.of("Survival of the Fittest", "Forest"), List.of());
        Map<String, Object> survival = card(d, "battlefield", "Survival of the Fittest");
        Assert.assertNull("a playable ability needs no reason: " + survival, survival.get("not_playable"));
        Assert.assertTrue("Survival is offered: " + d.get("choices"), offered(d, "Survival of the Fittest"));
    }

    @Test(timeout = 240_000)
    public void aTappedSourceSaysTapped() throws Exception {
        Map<String, Object> d = window("Begin Combat", NO_CREATURE, List.of("Forest", "Forest", "Icy Manipulator"), List.of(),
                (game, you) -> permanent(game, you, "Icy Manipulator").setTapped(true), null);
        Assert.assertEquals("tapped", reason(card(d, "battlefield", "Icy Manipulator")));
    }

    @Test(timeout = 240_000)
    public void aCreatureThatArrivedThisTurnSaysSummoningSick() throws Exception {
        // Cast it rather than cheat it down: the cheat takes the sickness off.
        Map<String, Object> d = window("YOUR_MAIN", "Prodigal Sorcerer", List.of("Island", "Island", "Island"), List.of(),
                null, "Prodigal Sorcerer");
        Assert.assertEquals("summoning sick", reason(card(d, "battlefield", "Prodigal Sorcerer")));
    }

    @Test(timeout = 240_000)
    public void aSorceryOutsideYourMainSaysSorcerySpeed() throws Exception {
        // Begin Combat: the stack is empty and it is our turn, but not a main
        // phase, so only instant-speed plays are legal.
        Map<String, Object> d = window("Begin Combat", "Grizzly Bears", List.of("Forest", "Forest"), List.of());
        Assert.assertEquals("sorcery speed", reason(card(d, "hand", "Grizzly Bears")));
    }

    @Test(timeout = 240_000)
    public void manalessSaysWhatItCostsAndWhatYouCouldMake() throws Exception {
        Map<String, Object> d = window("YOUR_MAIN", "Grizzly Bears", List.of(), List.of());
        Assert.assertEquals("needs {1}{G}, you can make nothing", reason(card(d, "hand", "Grizzly Bears")));
    }

    @Test(timeout = 240_000)
    public void oneForestIsNotTwoMana() throws Exception {
        Map<String, Object> d = window("YOUR_MAIN", "Grizzly Bears", List.of("Forest"), List.of());
        Assert.assertEquals("needs {1}{G}, you can make {G}", reason(card(d, "hand", "Grizzly Bears")));
    }

    // ---- helpers ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> card(Map<String, Object> d, String zone, String name) {
        Object cards = GameHostTest.board(d).get(zone);
        Assert.assertTrue(zone + " on the board: " + d, cards instanceof List<?>);
        for (Object o : (List<?>) cards) {
            Map<String, Object> card = (Map<String, Object>) o;
            if (name.equals(card.get("name"))) {
                return card;
            }
        }
        throw new AssertionError(name + " not in " + zone + ": " + cards);
    }

    @SuppressWarnings("unchecked")
    private static String reason(Map<String, Object> card) {
        Object why = card.get("not_playable");
        Assert.assertTrue("a reason on " + card, why instanceof Map<?, ?>);
        return String.valueOf(((Map<String, Object>) why).get("reason"));
    }

    private static String castIndex(Map<String, Object> d, String name) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("cast".equals(c.get("action")) && name.equals(c.get("name"))) {
                return String.valueOf(c.get("index"));
            }
        }
        return null;
    }

    private static boolean offered(Map<String, Object> d, String name) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if (name.equals(c.get("name"))) {
                return true;
            }
        }
        return false;
    }

    private static Permanent permanent(Game game, Player you, String name) {
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(you.getId())) {
            if (name.equals(perm.getName())) {
                return perm;
            }
        }
        throw new AssertionError(name + " is not on the battlefield");
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

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name, true);
        Assert.assertNotNull("card: " + name, info);
        return info.createCard();
    }
}
