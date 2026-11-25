package mage.player.seat;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.players.Player;
import org.junit.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A duel with a board built for the test: "You" (a seat) against "Opp" — a
 * second seat, or the CPU — over the harness decks, with the zones cheated
 * at the mulligan question (after the opening shuffle and draw, so a stacked
 * library top stays stacked and the hand is exactly the test's). "You" play
 * first, so nothing is drawn on turn 1. {@link #next} serves every seat:
 * it answers the opening questions and passes for the other seat, keeps
 * every result each seat was given, and hands back the next question for the
 * seat asked about.
 */
final class Fixture implements AutoCloseable {

    static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    final GameHost host;
    final Game game;
    final Player you;
    final Player opp;
    /** Every distinct result each seat was handed, in order. */
    final Map<String, List<Map<String, Object>>> seen = new LinkedHashMap<>();
    private final Consumer<Fixture> setup;
    private boolean built;

    /** {@code person}: the person's view (mana sources offered, payments asked); {@code oppSeat}: the opponent is a seat too. */
    Fixture(String id, boolean person, boolean oppSeat, Consumer<Fixture> setup) throws Exception {
        this.setup = setup;
        host = new GameHost(new GameHost.Config(id, "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0),
                        new GameHost.SeatSpec("Opp", oppSeat ? "seat" : "cpu", FILLER, 6)), person));
        game = host.game();
        Player y = null;
        Player o = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                y = p;
            } else {
                o = p;
            }
        }
        you = y;
        opp = o;
        seen.put("You", new ArrayList<>());
        if (oppSeat) {
            seen.put("Opp", new ArrayList<>());
        }
        host.start();
    }

    /** Clears the player's hand and puts the named cards where the test wants them; the library's last name is its top card. */
    void cheat(Player player, List<String> library, List<String> hand, List<String> battlefield, List<String> graveyard) {
        game.cheat(player.getId(), Map.of(Zone.HAND, "clear"));
        List<PutToBattlefieldInfo> perms = new ArrayList<>();
        for (String name : battlefield) {
            perms.add(new PutToBattlefieldInfo(card(name), false));
        }
        game.cheat(player.getId(), cards(library), cards(hand), perms, cards(graveyard), List.of(), List.of());
    }

    /** The next question for {@code seat} that is the test's to answer. */
    Map<String, Object> next(String seat) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            for (String s : seen.keySet()) {
                Map<String, Object> d = host.awaitDecision(s, s.equals(seat) ? 250 : 20);
                Assert.assertNull("the game ended: " + d, d.get("game_over"));
                Assert.assertNull("the engine died: " + d, d.get("engine_died"));
                if (!Boolean.TRUE.equals(d.get("action_pending"))) {
                    continue;
                }
                Assert.assertNull("render error: " + d, d.get("error"));
                List<Map<String, Object>> results = seen.get(s);
                if (results.isEmpty() || !d.get("game_seq").equals(results.get(results.size() - 1).get("game_seq"))) {
                    results.add(d);
                }
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    answer(s, Map.of("choice", index(d, "You")));
                } else if ("GAME_ASK".equals(type) && message.toLowerCase().contains("mulligan")) {
                    if (!built) {
                        built = true;
                        setup.accept(this);
                    }
                    answer(s, Map.of("choice", "no"));
                } else if (s.equals(seat)) {
                    Assert.assertTrue("the board was built before " + d, built);
                    return d;
                } else {
                    answer(s, Map.of("choice", "no"));
                }
            }
        }
        throw new AssertionError("no question for " + seat + " within two minutes");
    }

    void answer(String seat, Map<String, Object> args) {
        Map<String, Object> r = host.chooseAction(seat, args);
        Assert.assertTrue("answer rejected: " + r + " for " + args, Boolean.TRUE.equals(r.get("success")));
    }

    /** The index of the choice of that name (and action, when given), or a failure listing what was offered. */
    static String index(Map<String, Object> d, String name, String... action) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if (name.equals(c.get("name")) && (action.length == 0 || action[0].equals(c.get("action")))) {
                return String.valueOf(c.get("index"));
            }
        }
        throw new AssertionError(name + " is not offered: " + d.get("choices") + " in " + d.get("context"));
    }

    /** The card names of each looked-at pile on a result, pile by pile; empty when it carries none. */
    @SuppressWarnings("unchecked")
    static List<List<String>> looked(Map<String, Object> d) {
        List<List<String>> out = new ArrayList<>();
        if (d.get("looked_at") instanceof List<?> piles) {
            for (Object p : piles) {
                List<String> names = new ArrayList<>();
                for (Object c : (List<?>) ((Map<String, Object>) p).get("cards")) {
                    names.add(String.valueOf(((Map<String, Object>) c).get("name")));
                }
                out.add(names);
            }
        }
        return out;
    }

    static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name, true);
        Assert.assertNotNull("card: " + name, info);
        return info.createCard();
    }

    private static List<Card> cards(List<String> names) {
        List<Card> out = new ArrayList<>();
        for (String name : names) {
            out.add(card(name));
        }
        return out;
    }

    @Override
    public void close() {
        host.end();
    }
}
