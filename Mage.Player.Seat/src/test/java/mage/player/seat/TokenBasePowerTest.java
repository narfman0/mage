package mage.player.seat;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.game.command.emblems.AjaniResoluteEmblem;
import mage.game.permanent.token.BeastToken;
import mage.players.Player;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A token's *printed* power/toughness beside its current one: token art is
 * keyed by the pair the token was created with (a 3/3 and a 4/4 "Beast" are
 * different prints), so the board sends `base_power` / `base_toughness` on a
 * creature token. A 3/3 Beast token under Ajani's emblem ("Creatures you
 * control get +2/+2") reads 5/5 now and 3/3 as created; a Grizzly Bears
 * beside it reads 4/4 now and 2/2 printed — the same pair on a card, so the
 * board can say a number is not the printed one (PrintedPowerTest has the
 * permanents that are no longer their card).
 */
public class TokenBasePowerTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void aPumpedTokenKeepsThePrintedPowerToughnessItWasCreatedWith() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("tokenpt", "duel", 5L, null,
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
            library.add(card("Forest"));
        }
        game.cheat(you.getId(), library, List.of(), List.of(new PutToBattlefieldInfo(card("Grizzly Bears"), false)),
                List.of(), List.of(), List.of());
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
                    // The game thread is parked on this question: a safe moment
                    // to make a token and hand out the anthem that pumps it.
                    new BeastToken().putOntoBattlefield(1, game, null, you.getId());
                    game.addEmblem(new AjaniResoluteEmblem(), null, you.getId());
                    given = true;
                    args = Map.of("choice", "no");
                } else if (given) {
                    Map<String, Object> board = GameHostTest.board(d);
                    Map<String, Object> beast = null;
                    Map<String, Object> bears = null;
                    for (Object o : (List<?>) board.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        if (String.valueOf(perm.get("name")).startsWith("Beast")) {
                            beast = perm;
                        } else if ("Grizzly Bears".equals(perm.get("name"))) {
                            bears = perm;
                        }
                    }
                    Assert.assertNotNull("the token is on the board: " + board, beast);
                    Assert.assertEquals(true, beast.get("token"));
                    // As created, whatever the anthem is doing to it now.
                    Assert.assertEquals("3", beast.get("base_power"));
                    Assert.assertEquals("3", beast.get("base_toughness"));
                    // A printed card carries its printed pair the same way.
                    Assert.assertNotNull("our Bears is on the board: " + board, bears);
                    Assert.assertNull(bears.get("token"));
                    Assert.assertEquals("2", bears.get("base_power"));
                    Assert.assertEquals("2", bears.get("base_toughness"));
                    if ("5".equals(beast.get("power"))) { // the emblem has taken effect
                        Assert.assertEquals("5", beast.get("toughness"));
                        Assert.assertEquals("4", bears.get("power"));
                        return;
                    }
                    args = Map.of("choice", "no");
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("the emblem never pumped the token");
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
