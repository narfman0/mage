package mage.player.seat;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.players.Player;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A long list question: Cavern of Souls names a creature type as it enters,
 * one item per type. The decision says it is searchable, and a text answer
 * ("Elf") is taken like a pick.
 */
public class ChoicePromptTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void aCreatureTypeChoiceIsSearchableAndAnsweredByText() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("choice", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Cavern of Souls")), List.of(), List.of(), List.of(), List.of());
        host.start();
        try {
            boolean played = false;
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_CHOOSE_CHOICE".equals(type)) {
                    List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                    Assert.assertTrue("a long list: " + choices.size(), choices.size() > 100);
                    Assert.assertEquals("searchable: " + d.get("message"), Boolean.TRUE, d.get("searchable"));
                    Assert.assertTrue(choices.stream().anyMatch(c -> "Elf".equals(c.get("description"))));
                    args = Map.of("text", "Elf");
                } else if (played && "GAME_SELECT".equals(type)) {
                    Map<String, Object> board = GameHostTest.board(d);
                    Assert.assertTrue("the Cavern is down: " + board, GameHostTest.battlefieldHas(board, "Cavern of Souls"));
                    for (Object o : (List<?>) board.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        if ("Cavern of Souls".equals(perm.get("name"))) {
                            Assert.assertTrue("the chosen type is on the card: " + perm, String.valueOf(perm.get("rules")) + perm.get("hints") != null
                                    && (String.valueOf(perm.get("rules")).contains("Elf") || String.valueOf(perm.get("hints")).contains("Elf")));
                        }
                    }
                    return;
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && landIndex(d) != null) {
                    played = true;
                    args = Map.of("choice", landIndex(d));
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never played the Cavern");
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

    private static String landIndex(Map<String, Object> d) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("land".equals(c.get("action")) && "Cavern of Souls".equals(c.get("name"))) {
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
