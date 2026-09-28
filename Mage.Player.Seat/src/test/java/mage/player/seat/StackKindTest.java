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
 * Every stack item says what put it there: {@code kind} is {@code spell} for
 * a spell, {@code triggered} for a triggered ability and {@code activated}
 * for any other ability. The product's "Pass until the stack resolves" reads
 * a new trigger as the stack resolving and a new spell or activation as
 * someone responding (fullpod docs/board-ui.md "Hold Pass").
 */
public class StackKindTest {

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    /**
     * Under full control with a Soul Warden out: cast Grizzly Bears and the
     * next window has the spell on top; pass it and the next has the Warden's
     * trigger on top, with the Warden as its source.
     */
    @Test(timeout = 240_000)
    public void aSpellThenTheTriggerItsResolutionAdds() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("stackkind", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
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
        perms.add(new PutToBattlefieldInfo(card("Soul Warden"), false));
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Grizzly Bears")), perms, List.of(), List.of(), List.of());
        host.setFullControl("You", true);
        host.start();
        try {
            boolean cast = false;
            boolean sawSpell = false;
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over before the trigger: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Map<String, Object> args;
                if (cast && d.get("stack") instanceof List<?> stack && !stack.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> top = (Map<String, Object>) stack.get(0);
                    if (!sawSpell) {
                        Assert.assertEquals("the cast is on top: " + top, "Grizzly Bears", top.get("name"));
                        Assert.assertEquals("spell", top.get("kind"));
                        sawSpell = true;
                    } else {
                        Assert.assertEquals("the trigger its resolution added: " + top, "Soul Warden", top.get("source_card"));
                        Assert.assertEquals("triggered", top.get("kind"));
                        return;
                    }
                    args = Map.of("choice", "no");
                } else if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d) != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(d));
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never saw the Warden's trigger on the stack");
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

    private static String castIndex(Map<String, Object> d) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("cast".equals(c.get("action")) && "Grizzly Bears".equals(c.get("name"))) {
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
