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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Convoke through the seat: the mana prompt offers the engine's special
 * action ("Convoke"), answering it asks which creature to tap, and the
 * prompt comes back for what is still owed. The engine's order is sources
 * first (a mana ability can't pay once a special payment has been used on
 * the spell), and the prompt says so. Siege Wurm ({5}{G}{G}) with four Bears
 * and three Forests: a person's seat (mana sources offered) is asked rather
 * than paid from the Forests; three Forests, then four convokes, and the
 * Wurm resolves with every Bear tapped.
 */
public class SpecialManaActionTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void convokeIsOfferedAndPays() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("convoke", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), true));
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
        for (int i = 0; i < 4; i++) {
            perms.add(new PutToBattlefieldInfo(card("Grizzly Bears"), false));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Siege Wurm")), perms, List.of(), List.of(), List.of());
        host.start();
        int convokes = 0;
        int manaPrompts = 0;
        List<String> prompts = new ArrayList<>();
        try {
            boolean cast = false;
            for (int i = 0; i < 80; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_PLAY_MANA".equals(type)) {
                    manaPrompts++;
                    prompts.add(message);
                    Map<String, Object> special = null;
                    Map<String, Object> land = null;
                    for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                        if ("special".equals(c.get("choice_type")) && special == null) {
                            special = c;
                        } else if ("tap_source".equals(c.get("choice_type")) && land == null) {
                            land = c;
                        }
                    }
                    Assert.assertNotNull("Convoke offered on every round: " + d, special);
                    Assert.assertEquals("Convoke", special.get("name"));
                    Assert.assertEquals("special", special.get("id"));
                    Assert.assertTrue(String.valueOf(special.get("ability")).startsWith("Convoke ("));
                    Assert.assertTrue("the order is said: " + d.get("note"), String.valueOf(d.get("note")).startsWith("Tap sources first; Convoke"));
                    if (land != null) {
                        // The engine's order: sources first, the special action for the rest.
                        args = Map.of("choice", String.valueOf(land.get("index")));
                    } else {
                        convokes++;
                        args = Map.of("choice", "special");
                    }
                } else if ("GAME_TARGET".equals(type) && cast) {
                    Assert.assertTrue("the convoke target prompt: " + message, message.toLowerCase().contains("convoke"));
                    args = Map.of("choice", "0");
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d) != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(d));
                } else if (cast && "GAME_SELECT".equals(type)) {
                    // Priority after the payment: the Wurm is on the stack or resolved.
                    Map<String, Object> board = GameHostTest.board(d);
                    Set<String> tapped = new HashSet<>();
                    boolean wurm = false;
                    for (Object o : (List<?>) board.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        if ("Siege Wurm".equals(perm.get("name"))) {
                            wurm = true;
                        } else if (Boolean.TRUE.equals(perm.get("tapped"))) {
                            tapped.add(perm.get("name") + "#" + perm.get("id"));
                        }
                    }
                    Assert.assertTrue("Siege Wurm resolved: " + board, wurm || d.get("stack") != null);
                    Assert.assertEquals("four Bears convoked: " + tapped, 4, tapped.stream().filter(t -> t.startsWith("Grizzly Bears")).count());
                    Assert.assertEquals("four convokes: " + prompts, 4, convokes);
                    Assert.assertEquals("three Forests through the prompt, then four convokes: " + prompts, 7, manaPrompts);
                    Assert.assertTrue("the first prompt asks for the whole cost: " + prompts, prompts.get(0).startsWith("Pay {5}{G}{G}"));
                    Assert.assertTrue("after the Forests, {4} is left for the Bears: " + prompts, prompts.get(3).startsWith("Pay {4}"));
                    return;
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never got past the payment: " + prompts);
        } finally {
            host.end();
        }
    }

    /**
     * Delve: Treasure Cruise ({7}{U}) with one Island and seven cards in the
     * graveyard. The Island first (the engine's order), then "Delve" — one
     * special action whose cost is a pick of up to seven graveyard cards,
     * asked as a several-targets prompt (chosen / Done) — and the Cruise
     * resolves: seven cards in exile, three drawn.
     */
    @Test(timeout = 240_000)
    public void delveIsOfferedAndPays() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("delve", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), true));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<PutToBattlefieldInfo> perms = List.of(new PutToBattlefieldInfo(card("Island"), false));
        List<Card> library = new ArrayList<>();
        List<Card> graveyard = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
            graveyard.add(card("Grizzly Bears"));
        }
        game.cheat(you.getId(), library, List.of(card("Treasure Cruise")), perms, graveyard, List.of(), List.of());
        host.start();
        List<String> prompts = new ArrayList<>();
        int picks = 0;
        try {
            boolean cast = false;
            for (int i = 0; i < 80; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_PLAY_MANA".equals(type)) {
                    prompts.add(message);
                    Map<String, Object> special = null;
                    Map<String, Object> land = null;
                    for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                        if ("special".equals(c.get("choice_type")) && special == null) {
                            special = c;
                        } else if ("tap_source".equals(c.get("choice_type")) && land == null) {
                            land = c;
                        }
                    }
                    Assert.assertNotNull("Delve offered: " + d, special);
                    Assert.assertEquals("Delve", special.get("name"));
                    args = land != null ? Map.of("choice", String.valueOf(land.get("index"))) : Map.of("choice", "special");
                } else if ("GAME_TARGET".equals(type) && cast) {
                    // The delve cost's pick: every graveyard card, one round each, then Done.
                    List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                    if (choices.isEmpty()) {
                        Assert.assertEquals("seven chosen: " + d, 7, ((List<?>) d.get("chosen")).size());
                        Assert.assertEquals("Done", d.get("done_text"));
                        args = Map.of("choice", "no");
                    } else {
                        picks++;
                        args = Map.of("choice", String.valueOf(choices.get(0).get("id")));
                    }
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d, "Treasure Cruise") != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(d, "Treasure Cruise"));
                } else if (cast && "GAME_SELECT".equals(type) && d.get("stack") == null) {
                    Map<String, Object> board = GameHostTest.board(d);
                    Assert.assertEquals("seven cards delved away: " + board, 7, ((List<?>) board.get("exile")).size());
                    Assert.assertEquals("the graveyard holds the resolved Cruise alone: " + board, 1, ((List<?>) board.get("graveyard")).size());
                    // The engine's dynamic hint on the delve card rides apart from its rules.
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cruise = (Map<String, Object>) ((List<?>) board.get("graveyard")).get(0);
                    Assert.assertEquals(List.of(Map.of("text", "Cards in your graveyard: 1")), cruise.get("hints"));
                    Assert.assertTrue("rules without the marker: " + cruise, String.valueOf(cruise.get("rules")).contains("Draw three cards.")
                            && !String.valueOf(cruise.get("rules")).contains("hintstart"));
                    Assert.assertEquals("drew three: " + board, 60 - 3, ((Number) board.get("library_size")).intValue());
                    Assert.assertEquals("seven picks: " + prompts, 7, picks);
                    Assert.assertEquals("the Island, then the delve: " + prompts, 2, prompts.size());
                    Assert.assertTrue(prompts.get(1).startsWith("Pay {7}"));
                    return;
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never got past the payment: " + prompts);
        } finally {
            host.end();
        }
    }

    private static String castIndex(Map<String, Object> d, String spell) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("cast".equals(c.get("action")) && spell.equals(c.get("name"))) {
                return String.valueOf(c.get("index"));
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
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("cast".equals(c.get("action")) && "Siege Wurm".equals(c.get("name"))) {
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
