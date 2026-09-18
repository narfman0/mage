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
 * A library whose top is public: Courser of Kruphix reveals it and lets its
 * controller play lands from there. The board carries the top card
 * (`top_card`, with its id), and playing it is a `land` choice `from:
 * library` rather than an "activate" of an object no view could find.
 */
public class LibraryTopTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void theRevealedTopIsOnTheBoardAndPlayableFromThere() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("librarytop", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<PutToBattlefieldInfo> perms = List.of(new PutToBattlefieldInfo(card("Courser of Kruphix"), false));
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Colossal Dreadmaw")), perms, List.of(), List.of(), List.of());
        // The start shuffles the library; the Forest goes on top once the game
        // is asking (the game thread parked), before the first main phase —
        // the starting player draws no card on turn one.
        Card forest = card("Forest");
        game.loadCards(java.util.Set.of(forest), you.getId());
        host.start();
        try {
            boolean played = false;
            boolean planted = false;
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if (!planted && "GAME_ASK".equals(type) && message.toLowerCase().contains("mulligan")) {
                    you.getLibrary().putOnTop(forest, game);
                    planted = true;
                    args = Map.of("choice", "no");
                } else if (!played && "GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && String.valueOf(d.get("context")).contains("YOUR_MAIN")) {
                    Map<String, Object> board = GameHostTest.board(d);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> top = (Map<String, Object>) board.get("top_card");
                    Assert.assertNotNull("the revealed top card is on the board: " + board, top);
                    Assert.assertEquals("Forest", top.get("name"));
                    Assert.assertNotNull(top.get("id"));
                    Map<String, Object> play = null;
                    for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                        if (top.get("id").equals(c.get("id"))) {
                            play = c;
                        }
                    }
                    Assert.assertNotNull("the top Forest is a choice: " + d, play);
                    Assert.assertEquals("a land drop, not an activation: " + play, "land", play.get("action"));
                    Assert.assertEquals("library", play.get("from"));
                    played = true;
                    args = Map.of("choice", String.valueOf(play.get("index")));
                } else if (played && "GAME_SELECT".equals(type)) {
                    Map<String, Object> board = GameHostTest.board(d);
                    Assert.assertTrue("the Forest came from the top: " + board, GameHostTest.battlefieldHas(board, "Forest"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> top = (Map<String, Object>) board.get("top_card");
                    Assert.assertNotNull("the next top is revealed too: " + board, top);
                    return;
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never played the top Forest");
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

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name);
        Assert.assertNotNull("card not in the DB: " + name, info);
        return info.createCard();
    }
}
