package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A commander game with two real precons — the product's format — driven by
 * the scripted seat: every decision the precons produce must render without
 * error and take an answer, and the commanders must be in the command zone.
 */
public class CommanderGameTest {

    private static final Logger LOG = Logger.getLogger(CommanderGameTest.class);

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 300_000)
    public void preconsPlayToTurnSix() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("commander", "commander", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", "src/test/resources/decks/heavenly_inferno.dck", 0),
                        new GameHost.SeatSpec("CPU", "cpu", "src/test/resources/decks/power_hungry.dck", 6)), true));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        int maxTurn = 0;
        boolean sawCommander = false;
        List<String> kinds = new ArrayList<>();
        try {
            for (int i = 0; i < 600; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertTrue("decision expected: " + d, Boolean.TRUE.equals(d.get("action_pending")));
                Assert.assertNull("render error on " + d.get("action_type") + ": " + d.get("error"), d.get("error"));
                String context = String.valueOf(d.get("context"));
                int turn = Integer.parseInt(context.substring(1, context.indexOf(' ')));
                maxTurn = Math.max(maxTurn, turn);
                String kind = d.get("action_type") + "/" + d.get("response_type") + (d.get("combat_phase") != null ? "/" + d.get("combat_phase") : "");
                if (!kinds.contains(kind)) {
                    kinds.add(kind);
                }
                Object commanders = GameHostTest.board(d).get("commanders");
                if (commanders instanceof List<?> l && !l.isEmpty()) {
                    sawCommander = true;
                }
                LOG.info("DECISION " + context + " " + kind + " " + d.get("message") + " choices=" + ScriptedSeat.choices(d).size());
                if (turn >= 6) {
                    break;
                }
                Map<String, Object> answer = host.chooseAction("You", script.answer(d));
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        LOG.info("kinds seen: " + kinds + "; decisions " + script.seen.size() + "; mana prompts " + script.manaPrompts);
        Assert.assertTrue("reached turn " + maxTurn, maxTurn >= 6);
        Assert.assertTrue("commanders in the command zone", sawCommander);
        Assert.assertEquals("life total is commander's", 40, ((Number) GameHostTest.board(script.seen.get(2)).get("life")).intValue());
    }
}
