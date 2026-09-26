package mage.player.seat;

import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.InfoEffect;
import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.game.permanent.Permanent;
import mage.players.Player;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The printed power/toughness (`base_power` / `base_toughness`) is sent only
 * while the permanent is still the card it was printed as. A Hill Giant that
 * has become a copy of Grizzly Bears is a 2/2 whose card says 3/3, and a
 * manifested Centaur Courser is a 2/2 whose card is hidden: both carry the
 * current pair only, so the board never colours a number against the wrong
 * print. The Bears beside them keeps its 2/2, and so does the opponent's
 * Runeclaw Bear: the pair comes from the card in the game, not from the
 * view's `original`, which XMage builds only for what the viewer controls.
 */
public class PrintedPowerTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void aCopyAndAFaceDownCreatureCarryNoPrintedPair() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("printedpt", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        Player cpu = null;
        for (Player p : game.getPlayers().values()) {
            if ("CPU".equals(p.getName())) {
                cpu = p;
            }
        }
        Assert.assertNotNull(cpu);
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Forest"));
        }
        Card bears = card("Grizzly Bears");
        Card giant = card("Hill Giant");
        Card courser = card("Centaur Courser");
        game.cheat(you.getId(), library, List.of(),
                List.of(new PutToBattlefieldInfo(bears, false), new PutToBattlefieldInfo(giant, false), new PutToBattlefieldInfo(courser, false)),
                List.of(), List.of(), List.of());
        List<Card> theirLibrary = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            theirLibrary.add(card("Forest"));
        }
        game.cheat(cpu.getId(), theirLibrary, List.of(), List.of(new PutToBattlefieldInfo(card("Runeclaw Bear"), false)),
                List.of(), List.of(), List.of());
        host.start();
        try {
            boolean staged = false;
            for (int i = 0; i < 40; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if (!staged && "GAME_ASK".equals(type) && message.toLowerCase().contains("mulligan")) {
                    // The game thread is parked on this question: a safe moment
                    // to turn the Giant into a copy of the Bears and the
                    // Courser face down.
                    Permanent bearsPerm = game.getPermanent(bears.getId());
                    Permanent giantPerm = game.getPermanent(giant.getId());
                    Permanent courserPerm = game.getPermanent(courser.getId());
                    Assert.assertNotNull(bearsPerm);
                    Assert.assertNotNull(giantPerm);
                    Assert.assertNotNull(courserPerm);
                    Ability source = new SimpleStaticAbility(new InfoEffect("test copy"));
                    source.setSourceId(giantPerm.getId());
                    source.setControllerId(you.getId());
                    game.copyPermanent(bearsPerm, giantPerm.getId(), source, null);
                    courserPerm.setManifested(true);
                    courserPerm.setFaceDown(true, game);
                    staged = true;
                    args = Map.of("choice", "no");
                } else if (staged) {
                    Map<String, Object> board = GameHostTest.board(d);
                    Map<String, Object> printedBears = null;
                    Map<String, Object> copy = null;
                    Map<String, Object> faceDown = null;
                    for (Object o : (List<?>) board.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        if (Boolean.TRUE.equals(perm.get("copy"))) {
                            copy = perm;
                        } else if (Boolean.TRUE.equals(perm.get("face_down"))) {
                            faceDown = perm;
                        } else if ("Grizzly Bears".equals(perm.get("name"))) {
                            printedBears = perm;
                        }
                    }
                    Assert.assertNotNull("the Bears is on the board: " + board, printedBears);
                    Assert.assertEquals("2", printedBears.get("base_power"));
                    Assert.assertEquals("2", printedBears.get("base_toughness"));
                    // The Giant is a 2/2 Bears now; its card's 3/3 is not its printed pair.
                    Assert.assertNotNull("the copy is on the board: " + board, copy);
                    Assert.assertEquals("Grizzly Bears", copy.get("name"));
                    Assert.assertEquals("Hill Giant", copy.get("original_card"));
                    Assert.assertEquals("2", copy.get("power"));
                    Assert.assertNull("a copy carries no printed pair: " + copy, copy.get("base_power"));
                    Assert.assertNull(copy.get("base_toughness"));
                    // The Courser is a face-down 2/2; the card's 3/3 is hidden information.
                    Assert.assertNotNull("the face-down creature is on the board: " + board, faceDown);
                    Assert.assertEquals("manifest", faceDown.get("face_down_kind"));
                    Assert.assertNull("a face-down creature carries no printed pair: " + faceDown, faceDown.get("base_power"));
                    Assert.assertNull(faceDown.get("base_toughness"));
                    // The opponent's creature, seen from our seat, carries its pair too.
                    @SuppressWarnings("unchecked")
                    Map<String, Object> theirs = ((List<Map<String, Object>>) d.get("board")).get(1);
                    Map<String, Object> runeclaw = null;
                    for (Object o : (List<?>) theirs.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        if ("Runeclaw Bear".equals(perm.get("name"))) {
                            runeclaw = perm;
                        }
                    }
                    Assert.assertNotNull("the opponent's Bear is on the board: " + theirs, runeclaw);
                    Assert.assertEquals("2", runeclaw.get("base_power"));
                    Assert.assertEquals("2", runeclaw.get("base_toughness"));
                    return;
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never saw the staged board");
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
