package mage.player.seat;

import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.InfoEffect;
import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.constants.Zone;
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
 * `modified` on a permanent the viewer doesn't control (fullpod #79). XMage
 * builds the view's `original` only for what the viewer controls, so the
 * bridge compares an opponent's face-up permanent with its card read from
 * the game instead. Our Urborg makes the opponent's Forest a Swamp too, and
 * their Swiftfoot Boots make their Centaur Courser hexproof: both read
 * `modified`. Their Runeclaw Bear and the Boots themselves are their printed
 * selves and don't. Their manifested Hill Giant is a face-down 2/2 whose
 * card is hidden, and the flag says nothing about it either way.
 */
public class OpponentModifiedTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void anOpponentsChangedPermanentReadsModified() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("oppmodified", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
        Game game = host.game();
        Player you = null;
        Player cpu = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            } else if ("CPU".equals(p.getName())) {
                cpu = p;
            }
        }
        Assert.assertNotNull(you);
        Assert.assertNotNull(cpu);
        // Urborg starts in hand: a static effect on the battlefield before
        // the game starts trips XMage's not-started check.
        Card urborg = card("Urborg, Tomb of Yawgmoth");
        game.cheat(you.getId(), forests(), List.of(urborg), List.of(new PutToBattlefieldInfo(card("Forest"), false)),
                List.of(), List.of(), List.of());
        Card courser = card("Centaur Courser");
        Card boots = card("Swiftfoot Boots");
        Card giant = card("Hill Giant");
        game.cheat(cpu.getId(), forests(), List.of(),
                List.of(new PutToBattlefieldInfo(card("Forest"), false), new PutToBattlefieldInfo(card("Runeclaw Bear"), false),
                        new PutToBattlefieldInfo(courser, false), new PutToBattlefieldInfo(boots, false), new PutToBattlefieldInfo(giant, false)),
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
                    // The game thread is parked on this question: equip the
                    // Courser, play our Urborg and turn the Giant face down.
                    Permanent courserPerm = game.getPermanent(courser.getId());
                    Permanent bootsPerm = game.getPermanent(boots.getId());
                    Permanent giantPerm = game.getPermanent(giant.getId());
                    Assert.assertNotNull(courserPerm);
                    Assert.assertNotNull(bootsPerm);
                    Assert.assertNotNull(giantPerm);
                    Ability source = new SimpleStaticAbility(new InfoEffect("test equip"));
                    source.setSourceId(bootsPerm.getId());
                    source.setControllerId(cpu.getId());
                    Assert.assertTrue(courserPerm.addAttachment(bootsPerm.getId(), source, game));
                    Assert.assertTrue(you.moveCards(urborg, Zone.BATTLEFIELD, source, game));
                    giantPerm.setManifested(true);
                    giantPerm.setFaceDown(true, game);
                    staged = true;
                    args = Map.of("choice", "no");
                } else if (staged) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> board = (List<Map<String, Object>>) d.get("board");
                    Map<String, Object> ours = byName(board.get(0), "Forest");
                    Assert.assertEquals("our Forest under our Urborg: " + ours, true, ours.get("modified"));
                    Map<String, Object> theirs = board.get(1);
                    Map<String, Object> forest = byName(theirs, "Forest");
                    Assert.assertEquals("their Forest under our Urborg: " + forest, true, forest.get("modified"));
                    Map<String, Object> equipped = byName(theirs, "Centaur Courser");
                    Assert.assertEquals("their equipped Courser: " + equipped, true, equipped.get("modified"));
                    Assert.assertTrue(String.valueOf(equipped.get("rules")), String.valueOf(equipped.get("rules")).contains("Hexproof"));
                    Map<String, Object> bear = byName(theirs, "Runeclaw Bear");
                    Assert.assertNull("their plain Bear: " + bear, bear.get("modified"));
                    Map<String, Object> bootsView = byName(theirs, "Swiftfoot Boots");
                    Assert.assertNull("their Boots: " + bootsView, bootsView.get("modified"));
                    Map<String, Object> faceDown = null;
                    for (Object o : (List<?>) theirs.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        if (Boolean.TRUE.equals(perm.get("face_down"))) {
                            faceDown = perm;
                        }
                    }
                    Assert.assertNotNull("their face-down creature is on the board: " + theirs, faceDown);
                    Assert.assertNull("a face-down permanent carries no flag: " + faceDown, faceDown.get("modified"));
                    Assert.assertFalse("nor its card's name: " + faceDown, String.valueOf(faceDown).contains("Hill Giant"));
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

    private static Map<String, Object> byName(Map<String, Object> player, String name) {
        for (Object o : (List<?>) player.get("battlefield")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> perm = (Map<String, Object>) o;
            if (name.equals(perm.get("name"))) {
                return perm;
            }
        }
        throw new AssertionError(name + " is not on " + player.get("name") + "'s battlefield: " + player);
    }

    private static List<Card> forests() {
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Forest"));
        }
        return library;
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
