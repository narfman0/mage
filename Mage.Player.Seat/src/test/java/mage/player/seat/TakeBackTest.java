package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * Take back the mana tapped at priority: XMage's own UNDO through the
 * host. A Forest tapped at priority is untapped and its mana gone. A
 * completed cast is not undone in this XMage (PlayerImpl.cast resets the
 * bookmark once the spell is on the stack; only backing out mid-payment
 * is), so the window after your own spell under full control offers
 * nothing to take back — proved here so the flag never lies.
 */
public class TakeBackTest {

    private static final Logger LOG = Logger.getLogger(TakeBackTest.class);
    static final String BEARS = GameHostTest.BEARS;

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new java.util.ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 300_000)
    public void aManaTapIsTakenBackAndACompletedCastIsNot() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("takeback", "duel", 9L, null,
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), true));
        ScriptedSeat script = new ScriptedSeat();
        boolean tookBackMana = false;
        boolean tookBackCast = false;
        try {
            host.start();
            for (int i = 0; i < 400 && !(tookBackMana && tookBackCast); i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error", d.get("error"));
                List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                boolean priority = "GAME_SELECT".equals(d.get("action_type")) && d.get("combat_phase") == null && "select".equals(d.get("response_type"));
                Map<String, Object> mana = choices.stream().filter(c -> "mana".equals(c.get("action"))).findFirst().orElse(null);
                Map<String, Object> bears = choices.stream().filter(c -> "Grizzly Bears".equals(c.get("name")) && "cast".equals(c.get("action"))).findFirst().orElse(null);
                Map<String, Object> args;
                if (priority && !tookBackMana && mana != null && bears == null) {
                    // Nothing to take back before anything is done at this window.
                    Assert.assertNull("nothing to take back yet: " + d, d.get("can_take_back"));
                    Assert.assertEquals("no_take_back", host.takeBack("You").get("error_code"));
                    // Tap the Forest for mana: the engine asks again, with the tap to take back.
                    ok(host.chooseAction("You", Map.of("choice", String.valueOf(mana.get("index")))));
                    Map<String, Object> after = host.awaitDecision("You", 120_000);
                    Assert.assertEquals(Boolean.TRUE, after.get("can_take_back"));
                    Assert.assertNotNull("mana floating: " + GameHostTest.board(after), GameHostTest.board(after).get("mana_pool"));
                    Map<String, Object> r = host.takeBack("You");
                    Assert.assertEquals("took it back: " + r, Boolean.TRUE, r.get("success"));
                    Assert.assertTrue("the log says so", Fmt.stripHtml(String.join("\n", host.logLines())).contains("You takes back the mana they tapped"));
                    Map<String, Object> again = host.awaitDecision("You", 120_000);
                    Assert.assertNull("nothing left to take back: " + again, again.get("can_take_back"));
                    Assert.assertNull("the mana is gone: " + GameHostTest.board(again), GameHostTest.board(again).get("mana_pool"));
                    Assert.assertTrue("the Forest is untapped again", ScriptedSeat.choices(again).stream().anyMatch(c -> "mana".equals(c.get("action"))));
                    tookBackMana = true;
                    continue;
                }
                if (priority && tookBackMana && !tookBackCast && bears != null) {
                    // Under full control the window after the cast is asked, the spell
                    // on the stack — and the engine has spent the bookmark: nothing to
                    // take back, and the host says so rather than pretending.
                    host.setFullControl("You", true);
                    ok(host.chooseAction("You", Map.of("choice", String.valueOf(bears.get("index")))));
                    Map<String, Object> after = host.awaitDecision("You", 120_000);
                    Assert.assertTrue("Bears on the stack: " + after.get("stack"), String.valueOf(after.get("stack")).contains("Grizzly Bears"));
                    Assert.assertNull("a completed cast is not taken back: " + after, after.get("can_take_back"));
                    Assert.assertEquals("no_take_back", host.takeBack("You").get("error_code"));
                    host.setFullControl("You", false);
                    tookBackCast = true;
                    ok(host.chooseAction("You", Map.of("choice", "no")));
                    continue;
                }
                args = script.answer(d);
                ok(host.chooseAction("You", args));
            }
        } finally {
            host.end();
        }
        Assert.assertTrue("a mana tap was taken back", tookBackMana);
        Assert.assertTrue("a completed cast was checked", tookBackCast);
    }

    private static void ok(Map<String, Object> answer) {
        Assert.assertTrue("answer rejected: " + answer, Boolean.TRUE.equals(answer.get("success")));
    }
}
