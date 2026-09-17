package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Undo: a rollback requested while the seat holds a question restarts an earlier turn. */
public class RollbackTest {

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    private static int turnOf(Map<String, Object> d) {
        String context = String.valueOf(d.get("context"));
        return Integer.parseInt(context.substring(1, context.indexOf(' ')));
    }

    @Test(timeout = 120_000)
    public void rollbackOneTurnFromTurnThree() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("undo", "duel", 9L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", GameHostTest.BEARS, 6)), false));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        try {
            Map<String, Object> d = null;
            for (int i = 0; i < 100; i++) {
                d = host.awaitDecision("You", 60_000);
                Assert.assertTrue(String.valueOf(d), Boolean.TRUE.equals(d.get("action_pending")));
                if (turnOf(d) >= 3 && "GAME_SELECT".equals(d.get("action_type"))) {
                    break;
                }
                Assert.assertEquals(true, host.chooseAction("You", script.answer(d)).get("success"));
            }
            Assert.assertEquals(3, turnOf(d));
            Assert.assertTrue("rollback accepted", host.rollback("You", 1));
            // The engine restarts the earlier turn and asks again from there.
            Map<String, Object> after = null;
            for (int i = 0; i < 20; i++) {
                after = host.awaitDecision("You", 60_000);
                Assert.assertTrue(String.valueOf(after), Boolean.TRUE.equals(after.get("action_pending")));
                if (turnOf(after) < 3) {
                    break;
                }
                Assert.assertEquals(true, host.chooseAction("You", script.answer(after)).get("success"));
            }
            Assert.assertTrue("back before turn 3: " + after.get("context"), turnOf(after) < 3);
        } finally {
            host.end();
        }
    }
}
