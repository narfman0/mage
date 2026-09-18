package mage.player.seat;

import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.game.command.emblems.AjaniResoluteEmblem;
import mage.players.Player;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The command zone's non-commander objects: a planeswalker's emblem is a
 * permanent rule with no card on the board to read it from. Ajani's emblem
 * ("Creatures you control get +2/+2"), given to the seat once the game is
 * asking, shows on the board's `command_zone` with its rules.
 */
public class CommandZoneObjectsTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void anEmblemIsOnTheBoard() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("emblem", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        host.start();
        try {
            boolean given = false;
            for (int i = 0; i < 40; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if (!given && "GAME_ASK".equals(type) && message.toLowerCase().contains("mulligan")) {
                    // The game thread is parked on this question: a safe moment to hand out an emblem.
                    game.addEmblem(new AjaniResoluteEmblem(), null, you.getId());
                    given = true;
                    args = Map.of("choice", "no");
                } else if (given) {
                    Map<String, Object> board = GameHostTest.board(d);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> zone = (List<Map<String, Object>>) board.get("command_zone");
                    Assert.assertNotNull("the emblem is in the command zone: " + board, zone);
                    Assert.assertEquals(1, zone.size());
                    Map<String, Object> emblem = zone.get(0);
                    Assert.assertEquals("emblem", emblem.get("kind"));
                    Assert.assertNotNull(emblem.get("id"));
                    Assert.assertTrue("named after Ajani: " + emblem, String.valueOf(emblem.get("name")).contains("Ajani"));
                    Assert.assertTrue("its rule: " + emblem, String.valueOf(emblem.get("rules")).contains("+2/+2"));
                    Assert.assertNull("no commander in a duel: " + board, board.get("commanders"));
                    return;
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never saw a board after the emblem");
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
}
